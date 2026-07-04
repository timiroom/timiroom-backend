package com.timiroom.storage;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@Slf4j
@Service
public class StorageService {

    private final S3Client s3Client;
    private final String bucket;
    private final String publicUrl;

    public StorageService(
            @Value("${storage.endpoint}") String endpoint,
            @Value("${storage.access-key}") String accessKey,
            @Value("${storage.secret-key}") String secretKey,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.public-url}") String publicUrl) {

        this.bucket = bucket;
        this.publicUrl = publicUrl.replaceAll("/$", "");

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();

        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"AWS": ["*"]},
                        "Action": ["s3:GetObject"],
                        "Resource": ["arn:aws:s3:::%s/*"]
                      }]
                    }
                    """.formatted(bucket);
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(bucket).policy(policy).build());
            log.info("MinIO bucket '{}' created with public-read policy", bucket);
        } catch (Exception e) {
            log.warn("MinIO bucket check failed ({}). Storage may not be available.", e.getMessage());
        }
    }

    /** 프로필 이미지 전용 — 256×256 정사각형 크롭 + JPEG 85% 압축 후 저장 */
    public String uploadProfileImage(MultipartFile file, String folder) throws IOException {
        byte[] processed = processProfileImage(file.getBytes());
        return uploadBytes(processed, "image/jpeg", folder);
    }

    public String uploadProfileImage(byte[] bytes, String folder) throws IOException {
        byte[] processed = processProfileImage(bytes);
        return uploadBytes(processed, "image/jpeg", folder);
    }

    private byte[] processProfileImage(byte[] original) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(original))
                .size(256, 256)
                .crop(Positions.CENTER)
                .outputFormat("jpg")
                .outputQuality(0.85)
                .toOutputStream(out);
        return out.toByteArray();
    }

    public String upload(MultipartFile file, String folder) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        String key = folder + "/" + UUID.randomUUID() + ext;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType())
                        .contentLength(file.getSize())
                        .build(),
                RequestBody.fromBytes(file.getBytes()));

        return publicUrl + "/" + bucket + "/" + key;
    }

    public String upload(byte[] bytes, String contentType, String folder) throws IOException {
        return uploadBytes(bytes, contentType, folder);
    }

    private String uploadBytes(byte[] bytes, String contentType, String folder) {
        String ext = contentTypeToExt(contentType);
        String key = folder + "/" + UUID.randomUUID() + ext;

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));

        return publicUrl + "/" + bucket + "/" + key;
    }

    public void delete(String url) {
        if (url == null || url.isBlank()) return;
        String prefix = publicUrl + "/" + bucket + "/";
        if (!url.startsWith(prefix)) return;
        String key = url.substring(prefix.length());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("Failed to delete object {}: {}", key, e.getMessage());
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf("."));
    }

    private String contentTypeToExt(String contentType) {
        if (contentType == null) return ".jpg";
        return switch (contentType.split(";")[0].trim().toLowerCase()) {
            case "image/png"  -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif"  -> ".gif";
            default           -> ".jpg";
        };
    }
}
