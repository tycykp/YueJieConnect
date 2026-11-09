package io.github.yuejie.connect;
import org.junit.runner.*;
public final class FileTransferMethodCheck {
    public static void main(String[] args) throws Exception {
        Result result=new JUnitCore().run(Request.method(Class.forName(args[0]),args[1]));
        result.getFailures().forEach(f->System.out.println(f.getTrace()));
        System.out.println("Selected method: "+args[1]+"; tests="+result.getRunCount()+"; failures="+result.getFailureCount());
        if(!result.wasSuccessful())System.exit(1);
    }
}
