package com.aitaskcenter.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MinioClientFactory {
    Client create(MinioStorageConfig config) {
        String scheme = config.useSsl() ? "https://" : "http://";
        MinioClient delegate = MinioClient.builder()
                .endpoint(scheme + config.endpoint())
                .credentials(config.accessKeyId(), config.secretAccessKey())
                .build();
        return new SdkClient(delegate);
    }

    interface Client {
        boolean bucketExists(String bucket) throws Exception;
        void makeBucket(String bucket) throws Exception;
        void putObject(String bucket, String key, byte[] bytes, String contentType, boolean createOnly) throws Exception;
        InputStream getObject(String bucket, String key) throws Exception;
        ObjectMetadata statObject(String bucket, String key) throws Exception;
        void removeObject(String bucket, String key) throws Exception;
    }

    private record SdkClient(MinioClient delegate) implements Client {
        @Override
        public boolean bucketExists(String bucket) throws Exception {
            return delegate.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        }

        @Override
        public void makeBucket(String bucket) throws Exception {
            delegate.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        @Override
        public void putObject(String bucket, String key, byte[] bytes, String contentType, boolean createOnly)
                throws Exception {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                    .contentType(contentType);
            if (createOnly) builder.headers(Map.of("If-None-Match", "*"));
            try {
                delegate.putObject(builder.build());
            } catch (ErrorResponseException exception) {
                if (isAlreadyExists(exception)) throw new ObjectAlreadyExistsException();
                throw exception;
            }
        }

        @Override
        public InputStream getObject(String bucket, String key) throws Exception {
            try {
                return delegate.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            } catch (ErrorResponseException exception) {
                if (isMissing(exception)) throw new ObjectMissingException();
                throw exception;
            }
        }

        @Override
        public ObjectMetadata statObject(String bucket, String key) throws Exception {
            var response = delegate.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new ObjectMetadata(response.size(), response.contentType());
        }

        @Override
        public void removeObject(String bucket, String key) throws Exception {
            delegate.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        }

        private static boolean isAlreadyExists(ErrorResponseException exception) {
            String code = exception.errorResponse() == null ? "" : exception.errorResponse().code();
            return "PreconditionFailed".equals(code)
                    || (exception.response() != null && exception.response().code() == 412);
        }

        private static boolean isMissing(ErrorResponseException exception) {
            String code = exception.errorResponse() == null ? "" : exception.errorResponse().code();
            return "NoSuchKey".equals(code) || "NoSuchObject".equals(code)
                    || (exception.response() != null && exception.response().code() == 404);
        }
    }

    static final class ObjectAlreadyExistsException extends Exception { }
    static final class ObjectMissingException extends Exception { }
    record ObjectMetadata(long size, String contentType) { }
}
