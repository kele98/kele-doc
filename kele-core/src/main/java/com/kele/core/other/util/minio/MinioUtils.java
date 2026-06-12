package com.kele.core.other.util.minio;

import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.other.properties.MinioFileUploadProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * @author wuzhenhong
 * @date 2025/3/12 18:08
 */
@Slf4j
public class MinioUtils {

    public static final MinioClient createMinioClient(MinioFileUploadProperties minio) {
        if (Objects.isNull(minio)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件上传配置为 lx.doc.fileUpload.type=minio 时未配置 minio 属性");
        }

        String endpoint = minio.getEndpoint();
        if (!StringUtils.hasText(endpoint)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件上传配置为 lx.doc.fileUpload.type=minio 时 endpoint 必须配置");
        }

        String bucket = minio.getBucket();
        if (!StringUtils.hasText(bucket)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件上传配置为 lx.doc.fileUpload.type=minio 时 bucket 必须配置");
        }

        String accessKey = minio.getAccessKey();
        if (!StringUtils.hasText(accessKey)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件上传配置为 lx.doc.fileUpload.type=minio 时 accessKey 必须配置");
        }
        String secretKey = minio.getSecretKey();
        if (!StringUtils.hasText(secretKey)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件上传配置为 lx.doc.fileUpload.type=minio 时 secretKey 必须配置");
        }
        MinioClient minioClient = MinioClient.builder().endpoint(endpoint)
            .credentials(minio.getAccessKey(), minio.getSecretKey()).build();
        // 检查 bucket 是否存在
        boolean exists;
        try {
            exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "检查名为【" + bucket + "】的 bucket 是否存在时出错！", e);
        }
        if (!exists) {
            try {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[Kele-Doc] MinIO bucket【{}】不存在，已自动创建", bucket);
            } catch (Exception e) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    "自动创建名为【" + bucket + "】的 bucket 失败！", e);
            }
            // 设置桶策略为公开只读（封面图、附件等通过 /fs/ 直接访问）
            setBucketPublicRead(minioClient, bucket);
        }
        return minioClient;
    }

    /**
     * 设置桶策略为匿名只读（允许公开下载对象，不允许上传/删除）
     */
    private static void setBucketPublicRead(MinioClient minioClient, String bucket) {
        String policy = "{\n" +
            "  \"Statement\": [\n" +
            "    {\n" +
            "      \"Action\": [\"s3:GetObject\"],\n" +
            "      \"Effect\": \"Allow\",\n" +
            "      \"Principal\": {\"AWS\": [\"*\"]},\n" +
            "      \"Resource\": [\"arn:aws:s3:::" + bucket + "/*\"]\n" +
            "    }\n" +
            "  ],\n" +
            "  \"Version\": \"2012-10-17\"\n" +
            "}";
        try {
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucket).config(policy).build());
            log.info("[Kele-Doc] MinIO bucket【{}】已设置公开只读策略", bucket);
        } catch (Exception e) {
            log.warn("[Kele-Doc] MinIO bucket【{}】设置公开只读策略失败（不影响上传，仅影响匿名下载）：{}", bucket, e.getMessage());
        }
    }
}
