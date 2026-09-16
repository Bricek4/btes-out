package studio.agent.platform.importing;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

@Service
public class GitImportService {
  public List<String> branches(String rawUrl,String token){throw remoteGitDisabled();}
  public GitArchive archive(String rawUrl,String branch,String token){throw remoteGitDisabled();}
  private static IllegalStateException remoteGitDisabled(){return new IllegalStateException("remote Git import is disabled until an egress proxy with redirect and DNS-rebinding enforcement is configured; use ZIP import");}
  private static URI safeUri(String raw){try{var uri=URI.create(raw);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException("only HTTPS Git URLs are allowed");var address=InetAddress.getByName(uri.getHost());if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress())throw new IllegalArgumentException("private Git hosts are not allowed");return uri;}catch(java.net.UnknownHostException e){throw new IllegalArgumentException("Git host could not be resolved",e);}}
  private static byte[] zipDirectory(Path root)throws Exception{var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out);var paths=Files.walk(root)){for(var path:paths.filter(Files::isRegularFile).filter(p->!p.startsWith(root.resolve(".git"))).sorted().toList()){var name=root.relativize(path).toString().replace('\\','/');zip.putNextEntry(new ZipEntry(name));Files.copy(path,zip);zip.closeEntry();}}return out.toByteArray();}
  private static void delete(Path root){if(root==null)return;try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}}
  public record GitArchive(String commit,byte[] bytes){}
}
