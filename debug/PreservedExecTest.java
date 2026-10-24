// Usage: native_exec_preservation.sh; exercises the installed JDK launch path.
import java.nio.file.*;
import java.security.*;
import java.util.*;
public class PreservedExecTest {
 static native long reconcile(String root,String bin);
 public static void main(String[] args)throws Exception{
  System.load(Path.of(args[0]).toAbsolutePath().toString());
  Path root=Path.of(args[1]).toAbsolutePath();Path exe=root.resolve("arm64/libexec_fixture.so");
  byte[] before=Files.readAllBytes(Path.of(args[3]));
  if(reconcile(root.toString(),Path.of(args[2]).toAbsolutePath().toString())!=1)throw new AssertionError("fixture not installed");
  if(!Arrays.equals(before,Files.readAllBytes(exe)))throw new AssertionError("installed ELF bytes changed");
  for(boolean relative:new boolean[]{false,true}) {
   String command=relative?"./arm64/libexec_fixture.so":exe.toString();
   ProcessBuilder pb=new ProcessBuilder(command,"space in argument").directory(root.toFile());
   if(relative)pb.environment().clear();
   pb.environment().put("EXEC_FIXTURE_VALUE","environment value");
   Process p=pb.start();String out=new String(p.getInputStream().readAllBytes());String err=new String(p.getErrorStream().readAllBytes());
   int rc=p.waitFor();
   String expected=command+"\nspace in argument\nenvironment value\n"+root+"\n";
   if(rc!=73||!out.equals(expected)||!err.isEmpty())throw new AssertionError("rc="+rc+" stdout="+out+" stderr="+err);
  }
  Process host=new ProcessBuilder("/usr/bin/printf","host %s","passed").start();
  if(!new String(host.getInputStream().readAllBytes()).equals("host passed")||host.waitFor()!=0)throw new AssertionError("host launch changed");
  System.out.println("PASS: original ELF bytes, child argv, environment, cwd, stdout/stderr, exit status and host fallback");
 }
}
