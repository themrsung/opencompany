package com.coreintra.app.config;

import com.coreintra.documents.blob.BlobStore;
import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.internal.BinaryStore;
import com.coreintra.documents.service.BlobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where document bytes live.
 *
 * <p>A local volume, behind the {@link BlobStore} interface. §1 rules out
 * cloud-managed services and S3 for the shipped product, and the interface
 * exists so that an S3 implementation can be dropped in later without anything
 * above it noticing — which is only true if the choice is made here, once, and
 * never inside a service.
 *
 * <p>The store is constructed in the assembly module rather than annotated as a
 * component in the documents module, because the path is deployment
 * configuration and a domain module should not be reading {@code @Value} out of
 * the environment. It also keeps the documents module unit-testable against a
 * temporary directory with no Spring involved, which is how its tests run.
 */
@Configuration
public class DocumentStorageConfiguration {

    @Bean
    public BlobStore blobStore(@Value("${coreintra.blob-path:/var/lib/coreintra/blobs}") String path) {
        return new LocalFileBlobStore(path);
    }

    /**
     * The seam the format adapters use to fetch and store image bytes.
     *
     * <p>{@link BinaryStore} is the documents module's own small interface —
     * two methods — so that a test can implement it with a map. In production
     * it is {@link BlobService}, which means an image embedded in twenty
     * documents is stored once and every one of them is content-addressed the
     * same way. Adapting rather than making {@code BlobService} implement the
     * interface directly keeps the pivot model's dependency at two methods
     * instead of the whole blob service.
     */
    @Bean
    public BinaryStore binaryStore(final BlobService blobs) {
        return new BinaryStore() {
            @Override
            public byte[] load(String sha256) {
                return blobs.contentOf(sha256);
            }

            @Override
            public String store(byte[] content, String mediaType) {
                return blobs.store(content, mediaType, null).sha256();
            }
        };
    }
}
