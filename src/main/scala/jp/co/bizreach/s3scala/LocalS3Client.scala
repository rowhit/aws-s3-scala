package jp.co.bizreach.s3scala

import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util

import awscala.s3.{Bucket, PutObjectResult, S3 => AWScalaS3}
import com.amazonaws.metrics.RequestMetricCollector
import com.amazonaws.services.s3.model._
import org.apache.commons.codec.binary.Hex
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Mock implementation of S3 client which works on the local file system.
 *
 * @param dir the local root directory
 */
private[s3scala] class LocalS3Client(dir: java.io.File) extends com.amazonaws.services.s3.AmazonS3Client() with AWScalaS3 {

  // Create local root directory if it does not exist
  if(!dir.exists){
    dir.mkdirs()
  }

  override def createBucket(name: String): Bucket = super[S3].createBucket(name)

  override def putObject(putObjectRequest: PutObjectRequest): PutObjectResult = {
    val bucketName = putObjectRequest.getBucketName
    val key  = putObjectRequest.getKey
    val bucketDir = new File(dir, bucketName)
    if(!bucketDir.exists){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("All access to this object has been disabled")
    }

    val putFile = new File(bucketDir, key)
    val parentDir = putFile.getParentFile
    if(!parentDir.exists){
      parentDir.mkdirs()
    }

    if(putObjectRequest.getFile != null) {
      Files.copy(putObjectRequest.getFile.toPath, putFile.toPath)
    } else {
      IOUtils.write(putFile, IOUtils.toBytes(putObjectRequest.getInputStream))
    }
    if(putObjectRequest.getMetadata != null){
      val meta = putObjectRequest.getMetadata

      // TODO Remove this code in Scala 2.12.
      // Replace with pure java map because the map returned by asJava is not serializable in Scala 2.11.
      meta.setUserMetadata(toJavaMap(meta.getUserMetadata))

      val metaFile = new File(putFile.getParentFile, putFile.getName + ".meta")
      IOUtils.serializeObject(meta, metaFile)
    }

    // TODO set correct property value
    awscala.s3.PutObjectResult(new Bucket(bucketName), key, null, null, null, DateTime.now(), null, null)
  }

  private def toJavaMap(map: util.Map[String, String]): util.Map[String, String] = {
    val newMap = new util.HashMap[String, String]()
    map.asScala.foreach { case (key, value) => newMap.put(key, value) }
    newMap
  }

  override def getObject(getObjectRequest: GetObjectRequest): S3Object = {
    val bucketName = getObjectRequest.getBucketName
    val key        = getObjectRequest.getKey

    val bucketDir = new File(dir, bucketName)
    if(!bucketDir.exists){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("Access Denied")
    }

    val getFile = new File(bucketDir, key)
    if(!getFile.exists){
      null
    } else {
      val s3object = new S3Object()
      s3object.setBucketName(bucketName)
      s3object.setKey(key)
      s3object.setObjectContent(IOUtils.toInputStream(getFile))

      val metaFile = new File(getFile.getParentFile, getFile.getName + ".meta")

      val metadata = if(metaFile.exists()){
        IOUtils.deserializeObject[ObjectMetadata](metaFile)
      } else {
        val metadata = new ObjectMetadata()
        metadata.setContentLength(getFile.length)
        metadata
      }

      s3object.setObjectMetadata(metadata)
      s3object
    }
  }

  override def listObjects(
    listObjectsRequest: ListObjectsRequest
  ): ObjectListing = {
    new ObjectListing(){
      {
        val (baseCommonPrefixes, baseObjectSummaries) =
          createBaseTuple(
            dir,
            listObjectsRequest
          )

        setBucketName(listObjectsRequest.getBucketName)
        setPrefix(listObjectsRequest.getPrefix)
        setMarker(listObjectsRequest.getMarker)
        // Check null because argument type is primitive
        setMaxKeys(if(listObjectsRequest.getMaxKeys == null) 0 else listObjectsRequest.getMaxKeys)
        setDelimiter(listObjectsRequest.getDelimiter)
        setEncodingType(listObjectsRequest.getEncodingType)
        getCommonPrefixes
          .addAll(
            createCommonPrefixes(
              listObjectsRequest,
              baseCommonPrefixes
            )
          )
        getObjectSummaries
          .addAll(
            createObjectSummaries(
              listObjectsRequest,
              baseObjectSummaries
            )
          )
        setTruncated(false)
      }
    }
  }

  private[this] def createBaseTuple(
    dir: File,
    listObjectsRequest: ListObjectsRequest
  ): (Iterator[Path], Iterator[Path]) = {
    Try(
      Files.list(
        Paths.get(
          dir.getAbsolutePath,
          listObjectsRequest.getBucketName,
          listObjectsRequest.getPrefix
        )
      )
    ) match {
      case Success(s) =>
        s.iterator
          .asScala
          .partition(_.toFile.isDirectory && listObjectsRequest.getDelimiter != null)
      case Failure(t) =>
        (Iterator.empty, Iterator.empty)
    }
  }

  private[this] def createCommonPrefixes(
    listObjectsRequest: ListObjectsRequest,
    baseCommonPrefixes: Iterator[Path]
  ): java.util.List[String] = {
    baseCommonPrefixes.map(
      cp =>
        s"${listObjectsRequest.getPrefix}${cp.getFileName.toString}${listObjectsRequest.getDelimiter}"
    ).toList
      .distinct
      .sorted
      .asJava
  }

  private[this] def createObjectSummaries(
    listObjectsRequest: ListObjectsRequest,
    baseObjectSummaries: Iterator[Path]
  ): java.util.List[S3ObjectSummary] = {
    baseObjectSummaries.map{
      path =>
        new S3ObjectSummary(){
          {
            val attributes =
              Files.readAttributes(
                path,
                classOf[BasicFileAttributes]
              )

            setBucketName(listObjectsRequest.getBucketName)
            setKey(listObjectsRequest.getPrefix + path.getFileName.toString)
            setETag(
              Hex.encodeHex(
                java.security.MessageDigest.getInstance("MD5")
                .digest(
                  IOUtils.toBytes(
                    Files.newInputStream(path)
                  )
                )
              ).mkString
            )
            setSize(attributes.size)
            setLastModified(
              new java.util.Date(
                attributes.lastModifiedTime
                .toMillis
              )
            )
          }
        }
    }.toList
      .distinct
      .asJava
  }

  override def getS3AccountOwner(): Owner = ???

  override def listBuckets(listBucketsRequest: ListBucketsRequest): java.util.List[com.amazonaws.services.s3.model.Bucket] = {
    dir.listFiles.filter(_.isDirectory).map { child =>
      new com.amazonaws.services.s3.model.Bucket(child.getName)
    }.toList.asJava
  }

  override def getBucketLocation(getBucketLocationRequest: GetBucketLocationRequest): String = ???

  override def createBucket(createBucketRequest: CreateBucketRequest): com.amazonaws.services.s3.model.Bucket = {
    val bucketDir = new File(dir, createBucketRequest.getBucketName)
    if(bucketDir.exists){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("Your previous request to create the named bucket succeeded and you already own it.")
    }
    bucketDir.mkdir()
    new com.amazonaws.services.s3.model.Bucket(createBucketRequest.getBucketName)
  }

  override def getObjectAcl(bucketName: String, key: String, versionId: String): AccessControlList = ???

  override def setObjectAcl(bucketName: String, key: String, versionId: String, acl: AccessControlList): Unit = ???

  override def setObjectAcl(bucketName: String, key: String, versionId: String, acl: AccessControlList, requestMetricCollector: RequestMetricCollector): Unit = ???

  override def setObjectAcl(bucketName: String, key: String, versionId: String, acl: CannedAccessControlList): Unit = ???

  override def setObjectAcl(bucketName: String, key: String, versionId: String, acl: CannedAccessControlList, requestMetricCollector: RequestMetricCollector): Unit = ???

  override def getBucketAcl(bucketName: String): AccessControlList = ???

  override def getBucketAcl(getBucketAclRequest: GetBucketAclRequest): AccessControlList = ???

  override def setBucketAcl(bucketName: String, acl: AccessControlList): Unit = ???

  override def setBucketAcl(bucketName: String, acl: AccessControlList, requestMetricCollector: RequestMetricCollector): Unit = ???

  override def setBucketAcl(setBucketAclRequest: SetBucketAclRequest): Unit = ???

  override def setBucketAcl(bucketName: String, acl: CannedAccessControlList): Unit = ???

  override def setBucketAcl(bucketName: String, acl: CannedAccessControlList, requestMetricCollector: RequestMetricCollector): Unit = ???

  override def getObjectMetadata(getObjectMetadataRequest: GetObjectMetadataRequest): ObjectMetadata = {
    val bucketDir = new File(dir, getObjectMetadataRequest.getBucketName)
    val file = new File(bucketDir, getObjectMetadataRequest.getKey)
    if(!file.exists()){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("Not Found")
    } else {
      val metaFile = new File(file.getParentFile, file.getName + ".meta")
      if(metaFile.exists()){
        IOUtils.deserializeObject[ObjectMetadata](metaFile)
      } else {
        val metadata = new ObjectMetadata()
        metadata.setContentLength(file.length)
        metadata
      }
    }
  }

  override def doesBucketExist(bucketName: String): Boolean = new File(dir, bucketName).exists()

  override def getObject(getObjectRequest: GetObjectRequest, destinationFile: File): ObjectMetadata = ???

  override def deleteBucket(deleteBucketRequest: DeleteBucketRequest): Unit = {
    val bucketDir = new File(dir, deleteBucketRequest.getBucketName)
    if(!bucketDir.exists){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("Access Denied")
    }
    IOUtils.deleteDirectory(bucketDir)
  }

  override def copyObject(copyObjectRequest: CopyObjectRequest): CopyObjectResult = {
    val srcBucketDir = new File(dir, copyObjectRequest.getSourceBucketName)
    if(!srcBucketDir.exists) throw new com.amazonaws.services.s3.model.AmazonS3Exception("All access to this object has been disabled")
    val srcFile = new File(srcBucketDir, copyObjectRequest.getSourceKey)
    if(!srcFile.exists) throw new com.amazonaws.services.s3.model.AmazonS3Exception("Source object not found")

    val destBucketDir = new File(dir, copyObjectRequest.getDestinationBucketName)
    if(!destBucketDir.exists) throw new com.amazonaws.services.s3.model.AmazonS3Exception("All access to this object has been disabled")
    val destFile = new File(destBucketDir, copyObjectRequest.getDestinationKey)

    // Make all directories
    val parentDir = destFile.getParentFile
    if(!parentDir.exists){
      parentDir.mkdirs()
    }

    // Copy file
    Files.copy(srcFile.toPath, destFile.toPath, StandardCopyOption.REPLACE_EXISTING)

    // Copy meta file
    val srcMetaFile = new File(srcFile.getParentFile, srcFile.getName + ".meta")
    val destMetaFile = new File(destFile.getParentFile, destFile.getName + ".meta")
    Files.copy(srcMetaFile.toPath, destMetaFile.toPath, StandardCopyOption.REPLACE_EXISTING)

    new CopyObjectResult
  }

  override def copyPart(copyPartRequest: CopyPartRequest): CopyPartResult = ???

  override def deleteObject(deleteObjectRequest: DeleteObjectRequest): Unit = {
    val bucketDir = new File(dir, deleteObjectRequest.getBucketName)
    if(!bucketDir.exists){
      throw new com.amazonaws.services.s3.model.AmazonS3Exception("Access Denied")
    }

    val deleteFile = new File(bucketDir, deleteObjectRequest.getKey)
    if(deleteFile.exists){
      deleteFile.delete()
    }

    val metaFile = new File(deleteFile.getParentFile, deleteFile.getName + ".meta")
    if(metaFile.exists()){
      metaFile.delete()
    }
  }

  override def deleteObjects(deleteObjectsRequest: DeleteObjectsRequest): DeleteObjectsResult = ???

  override def deleteVersion(deleteVersionRequest: DeleteVersionRequest): Unit = ???

  override def setBucketVersioningConfiguration(setBucketVersioningConfigurationRequest: SetBucketVersioningConfigurationRequest): Unit = ???

  override def getBucketVersioningConfiguration(bucketName: String): BucketVersioningConfiguration = ???

  override def getBucketWebsiteConfiguration(getBucketWebsiteConfigurationRequest: GetBucketWebsiteConfigurationRequest): BucketWebsiteConfiguration = ???

  override def getBucketLifecycleConfiguration(bucketName: String): BucketLifecycleConfiguration = ???

  override def setBucketLifecycleConfiguration(setBucketLifecycleConfigurationRequest: SetBucketLifecycleConfigurationRequest): Unit = ???

  override def deleteBucketLifecycleConfiguration(deleteBucketLifecycleConfigurationRequest: DeleteBucketLifecycleConfigurationRequest): Unit = ???

  override def getBucketCrossOriginConfiguration(bucketName: String): BucketCrossOriginConfiguration = ???

  override def setBucketCrossOriginConfiguration(setBucketCrossOriginConfigurationRequest: SetBucketCrossOriginConfigurationRequest): Unit = ???

  override def deleteBucketCrossOriginConfiguration(deleteBucketCrossOriginConfigurationRequest: DeleteBucketCrossOriginConfigurationRequest): Unit = ???

  override def getBucketTaggingConfiguration(bucketName: String): BucketTaggingConfiguration = ???

  override def setBucketTaggingConfiguration(setBucketTaggingConfigurationRequest: SetBucketTaggingConfigurationRequest): Unit = ???

  override def deleteBucketTaggingConfiguration(deleteBucketTaggingConfigurationRequest: DeleteBucketTaggingConfigurationRequest): Unit = ???

  override def setBucketWebsiteConfiguration(setBucketWebsiteConfigurationRequest: SetBucketWebsiteConfigurationRequest): Unit = ???

  override def deleteBucketWebsiteConfiguration(deleteBucketWebsiteConfigurationRequest: DeleteBucketWebsiteConfigurationRequest): Unit = ???

  override def setBucketNotificationConfiguration(setBucketNotificationConfigurationRequest: SetBucketNotificationConfigurationRequest): Unit = ???

  override def getBucketNotificationConfiguration(bucketName: String): BucketNotificationConfiguration = ???

  override def getBucketLoggingConfiguration(bucketName: String): BucketLoggingConfiguration = ???

  override def setBucketLoggingConfiguration(setBucketLoggingConfigurationRequest: SetBucketLoggingConfigurationRequest): Unit = ???

  override def setBucketPolicy(bucketName: String, policyText: String): Unit = ???

  override def getBucketPolicy(getBucketPolicyRequest: GetBucketPolicyRequest): BucketPolicy = ???

  override def setBucketPolicy(setBucketPolicyRequest: SetBucketPolicyRequest): Unit = ???

  override def deleteBucketPolicy(deleteBucketPolicyRequest: DeleteBucketPolicyRequest): Unit = ???

  override def abortMultipartUpload(abortMultipartUploadRequest: AbortMultipartUploadRequest): Unit = ???

  override def completeMultipartUpload(completeMultipartUploadRequest: CompleteMultipartUploadRequest): CompleteMultipartUploadResult = ???

  override def initiateMultipartUpload(initiateMultipartUploadRequest: InitiateMultipartUploadRequest): InitiateMultipartUploadResult = ???

  override def listMultipartUploads(listMultipartUploadsRequest: ListMultipartUploadsRequest): MultipartUploadListing = ???

  override def listParts(listPartsRequest: ListPartsRequest): PartListing = ???

  override def uploadPart(uploadPartRequest: UploadPartRequest): UploadPartResult = ???

  override def restoreObject(restoreObjectRequest: RestoreObjectRequest): Unit = ???

}
