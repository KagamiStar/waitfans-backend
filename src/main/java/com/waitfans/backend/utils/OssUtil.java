package com.waitfans.backend.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLDecoder;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class OssUtil {
    @Value("${storage.provider:minio}")
    private String storageProvider;

    @Value("${oss.bucket}")
    private String OSS_BUCKET;

    @Value("${oss.bucketUrl}")
    private String OSS_BUCKET_URL;

    @Value("${directory.chunk}")
    private String CHUNK_DIRECTORY;   // 分片存储目录

    @Autowired(required = false)
    private OSS ossClient;

    @Autowired(required = false)
    private MinioClient minioClient;

    private boolean useMinio() {
        return "minio".equalsIgnoreCase(storageProvider);
    }

    private String publicUrl(String objectName) {
        return OSS_BUCKET_URL.endsWith("/")
                ? OSS_BUCKET_URL + objectName
                : OSS_BUCKET_URL + "/" + objectName;
    }

    /**
     * 将数据库中保存的公开访问地址还原为对象存储中的对象名。
     * 同时兼容阿里云 OSS 和本地 MinIO 的 bucketUrl 配置。
     */
    public String objectNameFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        String normalizedBase = OSS_BUCKET_URL.endsWith("/")
                ? OSS_BUCKET_URL
                : OSS_BUCKET_URL + "/";
        String objectName;
        if (url.startsWith(normalizedBase)) {
            objectName = url.substring(normalizedBase.length());
        } else {
            int schemeIndex = url.indexOf("://");
            int pathIndex = schemeIndex >= 0 ? url.indexOf('/', schemeIndex + 3) : -1;
            objectName = pathIndex >= 0 ? url.substring(pathIndex + 1) : url;
            if (objectName.startsWith(OSS_BUCKET + "/")) {
                objectName = objectName.substring(OSS_BUCKET.length() + 1);
            }
        }
        int queryIndex = objectName.indexOf('?');
        return (queryIndex >= 0 ? objectName.substring(0, queryIndex) : objectName)
                .replaceFirst("^/+", "");
    }

    private String contentType(MultipartFile file, String fallback) {
        return file.getContentType() == null ? fallback : file.getContentType();
    }

    private void putMinioObject(String objectName, InputStream stream, long size, String contentType)
            throws IOException {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(OSS_BUCKET)
                            .object(objectName)
                            .stream(stream, size, -1L)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("MinIO 上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 往阿里云对象存储上传单张图片
     * @param file 图片文件
     * @param type 图片分类，如 cover、carousel、other等，不允许空字符串，这里没有做判断了，自己注意就好
     * @return  图片的URL地址
     * @throws IOException
     */
    public String uploadImage(@NonNull MultipartFile file, @NonNull String type) throws IOException {
        // 生成文件名
        String originalFilename = file.getOriginalFilename();   // 获取原文件名
        String ext = "." + FilenameUtils.getExtension(originalFilename);    // 获取文件后缀
        String uuid = System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "");
        String fileName = uuid + ext;
        // 完整路径名
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date()).replace("-", "");
        String filePathName = date + "/img/" + type + "/" + fileName;
        if (useMinio()) {
            putMinioObject(
                    filePathName,
                    file.getInputStream(),
                    file.getSize(),
                    contentType(file, "application/octet-stream")
            );
            return publicUrl(filePathName);
        }
        try {
            ossClient.putObject(
                    OSS_BUCKET, // 仓库名
                    filePathName,   // 文件名（含路径）
                    file.getInputStream()   // 数据流
            );
        } catch (OSSException oe) {
            log.error("OSS出错了:" + oe.getErrorMessage());
            throw oe;
        } catch (ClientException ce) {
            log.error("OSS连接出错了:" + ce.getMessage());
            throw ce;
        }
        return publicUrl(filePathName);
    }

    /**
     * 往阿里云对象存储上传单个视频，简单上传
     * @param file 视频文件
     * @return  视频的URL地址
     * @throws IOException
     */
    public String uploadVideo(@NonNull MultipartFile file) throws IOException {
        // 生成文件名
        String originalFilename = file.getOriginalFilename();   // 获取原文件名
        String ext = "." + FilenameUtils.getExtension(originalFilename);    // 获取文件后缀
        String uuid = System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "");
        String fileName = uuid + ext;
        // 完整路径名
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date()).replace("-", "");
        String filePathName = date + "/video/" + fileName;
        if (useMinio()) {
            putMinioObject(
                    filePathName,
                    file.getInputStream(),
                    file.getSize(),
                    contentType(file, "video/mp4")
            );
            return publicUrl(filePathName);
        }
        try {
            ossClient.putObject(
                    OSS_BUCKET, // 仓库名
                    filePathName,   // 文件名（含路径）
                    file.getInputStream()   // 数据流
            );
        } catch (OSSException oe) {
            log.error("OSS出错了:" + oe.getErrorMessage());
            throw oe;
        } catch (ClientException ce) {
            log.error("OSS连接出错了:" + ce.getMessage());
            throw ce;
        }
        return publicUrl(filePathName);
    }

    /**
     * 将本地的视频分片文件追加合并上传到OSS
     * @param hash  视频的hash值，用于检索对应分片
     * @return  视频在OSS的URL地址
     * @throws IOException
     */
    public String appendUploadVideo(@NonNull String hash) throws IOException {
        // 生成文件名
        String uuid = System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "");
        String fileName = uuid + ".mp4";
        // 完整路径名
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date()).replace("-", "");
        String filePathName = date + "/video/" + fileName;
        if (useMinio()) {
            List<File> chunkFiles = new ArrayList<>();
            long totalSize = 0;
            for (int chunkIndex = 0; ; chunkIndex++) {
                File chunkFile = Paths.get(CHUNK_DIRECTORY, hash + "-" + chunkIndex).toFile();
                if (!chunkFile.exists()) {
                    if (chunkIndex == 0) {
                        log.error("没找到任何相关分片文件");
                        return null;
                    }
                    break;
                }
                chunkFiles.add(chunkFile);
                totalSize += chunkFile.length();
            }

            List<InputStream> streams = new ArrayList<>();
            try {
                for (File chunkFile : chunkFiles) {
                    streams.add(new BufferedInputStream(new FileInputStream(chunkFile)));
                }
                try (SequenceInputStream input =
                             new SequenceInputStream(Collections.enumeration(streams))) {
                    putMinioObject(filePathName, input, totalSize, "video/mp4");
                }
                for (File chunkFile : chunkFiles) {
                    if (!chunkFile.delete()) {
                        log.warn("未能删除已上传分片: " + chunkFile.getAbsolutePath());
                    }
                }
            } catch (IOException e) {
                for (InputStream stream : streams) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // 保留原始上传异常
                    }
                }
                throw e;
            }
            return publicUrl(filePathName);
        }
        ObjectMetadata meta = new ObjectMetadata();
        // 设置内容类型为MP4视频
        meta.setContentType("video/mp4");
        int chunkIndex = 0;
        long position = 0; // 追加位置
        while (true) {
            File chunkFile = Paths.get(CHUNK_DIRECTORY, hash + "-" + chunkIndex).toFile();
            if (!chunkFile.exists()) {
                if (chunkIndex == 0) {
                    log.error("没找到任何相关分片文件");
                    return null;
                }
                break;
            }
            // 读取分片数据
            FileInputStream fis = new FileInputStream(chunkFile);
            byte[] buffer = new byte[(int) chunkFile.length()];
            fis.read(buffer);
            fis.close();
            // 追加上传分片数据
            try {
                AppendObjectRequest appendObjectRequest = new AppendObjectRequest(OSS_BUCKET, filePathName, new ByteArrayInputStream(buffer), meta);
                appendObjectRequest.setPosition(position);
                AppendObjectResult appendObjectResult = ossClient.appendObject(appendObjectRequest);
                position = appendObjectResult.getNextPosition();
            } catch (OSSException oe) {
                log.error("OSS出错了:" + oe.getErrorMessage());
                throw oe;
            } catch (ClientException ce) {
                log.error("OSS连接出错了:" + ce.getMessage());
                throw ce;
            }
            chunkFile.delete(); // 上传完后删除分片
            chunkIndex++;
        }
        return publicUrl(filePathName);
    }

    /**
     * 往阿里云对象存储上传单个视频分片文件，在阿里云上设置了3天的生命周期，超过三天未合并使用的分片将被自动删除
     * 建议只在分布式系统使用，单体系统还是存在本地更好存取以及节省流量
     * @param file  分片文件
     * @param name  分片名，以 hash-index 命名
     * @return  是否上传成功，一般返回false是因为分片已存在
     * @throws IOException
     */
    public boolean uploadChunk(@NonNull MultipartFile file, @NonNull String name) throws IOException {
        String fileName = "chunk/" + name;  // 分片文件在OSS的存储路径名
        if (useMinio()) {
            try {
                minioClient.statObject(
                        StatObjectArgs.builder().bucket(OSS_BUCKET).object(fileName).build()
                );
                return false;
            } catch (ErrorResponseException e) {
                String code = e.errorResponse().code();
                if (!"NoSuchKey".equals(code) && !"NoSuchObject".equals(code)) {
                    throw new IOException("MinIO 查询分片失败: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                throw new IOException("MinIO 查询分片失败: " + e.getMessage(), e);
            }
            putMinioObject(
                    fileName,
                    file.getInputStream(),
                    file.getSize(),
                    contentType(file, "application/octet-stream")
            );
            return true;
        }
        boolean success = false;
        try {
            // 判断文件是否存在
            boolean found = ossClient.doesObjectExist(OSS_BUCKET, fileName);
            if (!found) {
                // 不存在才上传
                ossClient.putObject(OSS_BUCKET, fileName, file.getInputStream());
                success = true; // 上传成功
            }
        } catch (OSSException oe) {
            log.error("OSS出错了:" + oe.getErrorMessage());
            throw oe;
        } catch (ClientException ce) {
            log.error("OSS连接出错了:" + ce.getMessage());
            throw ce;
        }
        return success;
    }

    /**
     * 查询指定目录下，指定前缀的文件数量，阿里云限制了单次查询最多100条
     * @param prefix  要筛选的文件名前缀，包括目录路径，如果为空字符串，则查询全部文件
     * @return  指定目录下，指定前缀的文件数量
     */
    public int countFiles(@NonNull String prefix) {
        if (useMinio()) {
            int count = 0;
            try {
                Iterable<Result<Item>> results = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(OSS_BUCKET)
                                .prefix(prefix)
                                .recursive(true)
                                .build()
                );
                for (Result<Item> ignored : results) {
                    count++;
                }
                return count;
            } catch (Exception e) {
                throw new IllegalStateException("MinIO 文件统计失败: " + e.getMessage(), e);
            }
        }
        int count = 0;
        try {
            ListObjectsRequest listObjectsRequest = new ListObjectsRequest(OSS_BUCKET);
            listObjectsRequest.setPrefix(prefix);
            ObjectListing objectListing = ossClient.listObjects(listObjectsRequest);
            List<OSSObjectSummary> objectSummaries = objectListing.getObjectSummaries();
            count = objectSummaries.size();
        } catch (OSSException oe) {
            log.error("OSS出错了:" + oe.getErrorMessage());
            throw oe;
        } catch (ClientException ce) {
            log.error("OSS连接出错了:" + ce.getMessage());
            throw ce;
        }
        return count;
    }

    /**
     * 删除指定目录下指定前缀的所有文件，不允许目录和前缀都是空字符串
     * @param prefix    要筛选的文件名前缀，包括目录路径，不允许为空字符串
     */
    public void deleteFiles(@NonNull String prefix) {
        if (prefix.equals("")) {
            log.warn("你正试图删除整个bucket，已拒绝该危险操作");
            return;
        }
        if (useMinio()) {
            try {
                Iterable<Result<Item>> results = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(OSS_BUCKET)
                                .prefix(prefix)
                                .recursive(true)
                                .build()
                );
                for (Result<Item> result : results) {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(OSS_BUCKET)
                                    .object(result.get().objectName())
                                    .build()
                    );
                }
            } catch (Exception e) {
                log.error("MinIO 删除文件失败: " + e.getMessage(), e);
            }
            return;
        }
        try {
            // 列举所有包含指定前缀的文件并删除。
            String nextMarker = null;
            ObjectListing objectListing;
            do {
                ListObjectsRequest listObjectsRequest = new ListObjectsRequest(OSS_BUCKET).withPrefix(prefix).withMarker(nextMarker);
                objectListing = ossClient.listObjects(listObjectsRequest);
                if (objectListing.getObjectSummaries().size() > 0) {
                    List<String> keys = new ArrayList<>();
                    for (OSSObjectSummary s : objectListing.getObjectSummaries()) {
//                        System.out.println("key name: " + s.getKey());
                        keys.add(s.getKey());
                    }
                    DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(OSS_BUCKET).withKeys(keys).withEncodingType("url");
                    DeleteObjectsResult deleteObjectsResult = ossClient.deleteObjects(deleteObjectsRequest);
                    List<String> deletedObjects = deleteObjectsResult.getDeletedObjects();
                    try {
                        for(String obj : deletedObjects) {
                            String deleteObj =  URLDecoder.decode(obj, "UTF-8");
//                            log.info("删除文件：" + deleteObj);
                        }
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }
                nextMarker = objectListing.getNextMarker();
            } while (objectListing.isTruncated());
        } catch (OSSException oe) {
            log.error("OSS出错了:" + oe.getErrorMessage());
        } catch (ClientException ce) {
            log.error("OSS连接出错了:" + ce.getMessage());
        }
    }
}
