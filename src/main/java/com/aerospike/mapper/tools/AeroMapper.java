package com.aerospike.mapper.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.StringUtils;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.Statement;
import com.aerospike.mapper.tools.configuration.ClassConfig;
import com.aerospike.mapper.tools.configuration.Configuration;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class AeroMapper {

    private IAerospikeClient mClient;

    public static class Builder {
        private AeroMapper mapper;
        private List<Class<?>> classesToPreload = null;

        public Builder(IAerospikeClient client) {
            this.mapper = new AeroMapper(client);
        }

        /**
         * Add in a custom type converter. The converter must have methods which implement the ToAerospike and FromAerospike annotation
         *
         * @param converter
         * @return this object
         */
        public Builder addConverter(Object converter) {
            GenericTypeMapper mapper = new GenericTypeMapper(converter);
            TypeUtils.addTypeMapper(mapper.getMappedClass(), mapper);

            return this;
        }

        public Builder preLoadClass(Class<?> clazz) {
            if (classesToPreload == null) {
                classesToPreload = new ArrayList<>();
            }
            classesToPreload.add(clazz);
            return this;
        }

        public Builder withConfigurationFile(File file) throws JsonParseException, JsonMappingException, IOException {
        	return this.withConfigurationFile(file, false);
        }
        
        public Builder withConfigurationFile(File file, boolean allowsInvalid) throws JsonParseException, JsonMappingException, IOException {
        	ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        	Configuration configuration = objectMapper.readValue(file, Configuration.class);
        	this.loadConfiguration(configuration, allowsInvalid);
        	return this;
        }

        public Builder withConfiguration(String configurationYaml) throws JsonMappingException, JsonProcessingException {
        	return this.withConfiguration(configurationYaml, false);
        }
        
        public Builder withConfiguration(String configurationYaml, boolean allowsInvalid) throws JsonMappingException, JsonProcessingException {
        	ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        	Configuration configuration = objectMapper.readValue(configurationYaml, Configuration.class);
        	this.loadConfiguration(configuration, allowsInvalid);
        	return this;
        }

        private void loadConfiguration(@NotNull Configuration configuration, boolean allowsInvalid) {
        	for (ClassConfig config : configuration.getClasses()) {
        		try {
	        		String name = config.getClassName();
	        		if (StringUtils.isBlank(name)) {
	        			throw new AerospikeException("Class with blank name in configuration file");
	        		}
	        		else {
	        			try {
							ClassCache.getInstance().loadClass(this.mapper, config);
						} catch (ClassNotFoundException e) {
							throw new AerospikeException("Canot find a class with name " + name);
						}
	        		}
        		}
        		catch (RuntimeException re) {
        			if (allowsInvalid) {
        				System.err.println("Ignoring issue with configuration: " + re.getMessage());
        			}
        			else {
        				throw re;
        			}
        		}
        	}
        }
        
        public AeroMapper build() {
            if (classesToPreload != null) {
                for (Class<?> clazz : classesToPreload) {
                    ClassCache.getInstance().loadClass(clazz, this.mapper);
                }
            }
            return this.mapper;
        }
    }

    private AeroMapper(@NotNull IAerospikeClient client) {
        this.mClient = client;
    }

    public void save(@NotNull Object object) throws AerospikeException {
        this.save(null, object);
    }

    public void save(String namespace, @NotNull Object object) throws AerospikeException {
        Class<?> clazz = object.getClass();
        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);

        if (StringUtils.isBlank(namespace)) {
            namespace = entry.getNamespace();
            if (StringUtils.isBlank(namespace)) {
                throw new AerospikeException("Namespace not specified in annotation.");
            }
        }

        String set = entry.getSetName();
        int ttl = entry.getTtl();
        boolean sendKey = entry.getSendKey();

        long now = System.nanoTime();
        Key key = new Key(namespace, set, Value.get(entry.getKey(object)));

        Bin[] bins = entry.getBins(object);
        System.out.printf("Convert to bins in %,.3fms\n", ((System.nanoTime() - now) / 1_000_000.0));

        WritePolicy writePolicy = null;
        if (ttl != 0 || sendKey) {
            writePolicy = new WritePolicy();
            writePolicy.expiration = ttl;
            writePolicy.sendKey = sendKey;
        }
        now = System.nanoTime();
        mClient.put(writePolicy, key, bins);
        System.out.printf("Saved to database in %,.3fms\n", ((System.nanoTime() - now) / 1_000_000.0));
    }

    public <T> T readFromDigest(@NotNull Class<T> clazz, @NotNull byte[] digest) throws AerospikeException {
        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);
        String namespace = entry.getNamespace();
        if (StringUtils.isBlank(namespace)) {
            throw new AerospikeException("Namespace not specified in annotation.");
        }

        Key key = new Key(namespace, digest, entry.getSetName(), null);
        return this.read(clazz, key, entry);
    }

    public <T> T read(@NotNull Class<T> clazz, @NotNull Object userKey) throws AerospikeException {

        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);
        String namespace = entry.getNamespace();
        if (StringUtils.isBlank(namespace)) {
            throw new AerospikeException("Namespace not specified in annotation.");
        }

        String set = entry.getSetName();
        Key key = new Key(namespace, set, Value.get(entry.translateKeyToAerospikeKey(userKey)));
        return read(clazz, key, entry);
    }

    private <T> T read(@NotNull Class<T> clazz, @NotNull Key key, @NotNull ClassCacheEntry entry) {
        Record record = mClient.get(null, key);

        if (record == null) {
            return null;
        } else {
            try {
                T result = convertToObject(clazz, record, entry);
                return result;
            } catch (ReflectiveOperationException e) {
                throw new AerospikeException(e);
            }
        }
    }

    public boolean delete(@NotNull Class<?> clazz, @NotNull Object userKey) throws AerospikeException {
        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);
        Object asKey = entry.translateKeyToAerospikeKey(userKey);
        Key key = new Key(entry.getNamespace(), entry.getSetName(), Value.get(asKey));

        WritePolicy writePolicy = null;
        if (entry.getDurableDelete()) {
            writePolicy = new WritePolicy();
            writePolicy.durableDelete = entry.getDurableDelete();
        }

        return mClient.delete(writePolicy, key);
    }

    public boolean delete(@NotNull Object object) throws AerospikeException {
        Class<?> clazz = object.getClass();
        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);

        Key key = new Key(entry.getNamespace(), entry.getSetName(), Value.get(entry.getKey(object)));

        WritePolicy writePolicy = null;
        if (entry.getDurableDelete()) {
            writePolicy = new WritePolicy();
            writePolicy.durableDelete = entry.getDurableDelete();
        }
        return mClient.delete(writePolicy, key);
    }

    public <T> void find(@NotNull Class<T> clazz, Function<T, Boolean> function) throws AerospikeException {
        this.find(clazz, null, function);
    }

    public <T> void find(@NotNull Class<T> clazz, String namespace, Function<T, Boolean> function) throws AerospikeException {
        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);

        if (StringUtils.isBlank(namespace)) {
            namespace = entry.getNamespace();
            if (StringUtils.isBlank(namespace)) {
                throw new AerospikeException("Namespace not specified in annotation.");
            }
        }

        Statement statement = new Statement();
        statement.setNamespace(namespace);
        statement.setSetName(entry.getSetName());

        RecordSet recordSet = null;
        try {
            recordSet = mClient.query(null, statement);
            T result;
            while (recordSet.next()) {
                result = clazz.getConstructor().newInstance();
                entry.hydrateFromRecord(recordSet.getRecord(), result);
                if (!function.apply(result)) {
                    break;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AerospikeException(e);
        } finally {
            if (recordSet != null) {
                recordSet.close();
            }
        }

    }

    // --------------------------------------------------------------------------------------------------
    // The following are convenience methods to convert objects to / from lists / maps / records in case
    // it is needed to perform this operation manually. They will not be needed in most use cases.
    // --------------------------------------------------------------------------------------------------
    /**
     * Given a record loaded from Aerospike and a class type, attempt to convert the record to 
     * an instance of the passed class.
     * @param <T>
     * @param clazz
     * @param record
     * @return
     * @throws ReflectiveOperationException
     */
    public <T> T convertToObject(Class<T> clazz, Record record) {
    	try {
    		return convertToObject(clazz, record, null);
		} catch (ReflectiveOperationException e) {
			throw new AerospikeException(e);
		}    		
    }

    public <T> T convertToObject(Class<T> clazz, Record record, ClassCacheEntry entry) throws ReflectiveOperationException {
        if (entry == null) {
            entry = ClassCache.getInstance().loadClass(clazz, this);
        }
        T result = clazz.getConstructor().newInstance();
        entry.hydrateFromRecord(record, result);
        return result;
    }

    public <T> T convertToObject(Class<T> clazz, List<Object> record)  {
		try {
	        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);
	        T result;
			result = clazz.getConstructor().newInstance();
			entry.hydrateFromList(record, result);
			return result;
		} catch (ReflectiveOperationException e) {
			throw new AerospikeException(e);
		}
    }

    public <T> List<Object> convertToList(@NotNull T instance) {
    	ClassCacheEntry entry = ClassCache.getInstance().loadClass(instance.getClass(), this);
    	return entry.getList(instance, false);
    }

    public <T> T convertToObject(Class<T> clazz, Map<String,Object> record) {
    	try {
	        ClassCacheEntry entry = ClassCache.getInstance().loadClass(clazz, this);
	        T result = clazz.getConstructor().newInstance();
	        entry.hydrateFromMap(record, result);
	        return result;
		} catch (ReflectiveOperationException e) {
			throw new AerospikeException(e);
		}
    }

    public <T> Map<String, Object> convertToMap(@NotNull T instance) {
    	ClassCacheEntry entry = ClassCache.getInstance().loadClass(instance.getClass(), this);
    	return entry.getMap(instance);
    }
}
