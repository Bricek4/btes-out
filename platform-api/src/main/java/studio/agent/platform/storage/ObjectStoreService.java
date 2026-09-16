package studio.agent.platform.storage;

import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import studio.agent.platform.config.PlatformProperties;

@Service
public class ObjectStoreService {
  private final S3Client s3; private final S3Presigner presigner; private final String bucket;
  public ObjectStoreService(S3Client s3,S3Presigner presigner,PlatformProperties properties){this.s3=s3;this.presigner=presigner;this.bucket=properties.objectStore().bucket();}
  public void put(String key,byte[] bytes,String mediaType,String sha256){s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType).checksumSHA256(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha256))).build(),RequestBody.fromBytes(bytes));}
  public URL presignGet(String key,Duration duration){return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(duration).getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build()).url();}
  public URL presignDownload(String key,String filename,Duration duration){var disposition=ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString();return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(duration).getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).responseContentDisposition(disposition).build()).build()).url();}
  public URL presignPut(String key,String mediaType,long size,String sha256,Duration duration){return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(duration).putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType).contentLength(size).checksumSHA256(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha256))).build()).build()).url();}
  public Head head(String key){var h=s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build());return new Head(h.contentLength(),h.checksumSHA256());}
  public byte[] get(String key,long max){if(max<0)throw new IllegalArgumentException("read limit must be non-negative");try(var in=s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())){var bytes=in.readNBytes(Math.toIntExact(Math.min(max+1, Integer.MAX_VALUE)));if(bytes.length>max)throw new IllegalArgumentException("object exceeds read limit");return bytes;}catch(java.io.IOException e){throw new IllegalStateException("object read failed",e);}}
  public long copyTo(String key,long max,OutputStream output){if(max<0)throw new IllegalArgumentException("read limit must be non-negative");try(var in=s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())){var buffer=new byte[8192];long total=0;for(int count;(count=in.read(buffer))>=0;){if(count==0)continue;total=Math.addExact(total,count);if(total>max)throw new IllegalArgumentException("object exceeds read limit");output.write(buffer,0,count);}return total;}catch(java.io.IOException e){throw new IllegalStateException("object read failed",e);}}
  public record Head(long size,String checksumSha256){}
}
