package ru.practicum.ewm.stats.serialize;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

public class AvroDeserializer<T extends SpecificRecordBase> implements Deserializer<T> {

    private Class<T> targetType;

    public AvroDeserializer() {
    }

    public AvroDeserializer(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs, boolean isKey) {
        if (targetType == null) {
            targetType = (Class<T>) configs.get("avro.deserializer.target.class");
        }
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) return null;
        try {
            DatumReader<T> reader = new SpecificDatumReader<>(targetType);
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
            return reader.read(null, decoder);
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing Avro message", e);
        }
    }
}
