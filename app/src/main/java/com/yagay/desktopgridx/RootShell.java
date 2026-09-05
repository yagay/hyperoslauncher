package com.yagay.desktopgridx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    static final class Result {
        final int exitCode;
        final String output;
        final boolean timedOut;
        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode=exitCode; this.output=output; this.timedOut=timedOut;
        }
        boolean ok(){ return !timedOut && exitCode==0; }
    }

    private RootShell() {}

    static Result run(String command){ return run(command,30,4*1024*1024); }

    static Result run(String command,long timeoutSeconds,int maxBytes){
        Process p=null;
        try {
            p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            final Process proc=p;
            final ByteArrayOutputStream out=new ByteArrayOutputStream();
            final boolean[] truncated={false};
            Thread reader=new Thread(() -> {
                try(InputStream in=proc.getInputStream()) {
                    byte[] b=new byte[8192]; int n; int total=0;
                    while((n=in.read(b))>0) {
                        int keep=Math.min(n,Math.max(0,maxBytes-total));
                        if(keep>0) synchronized(out){ out.write(b,0,keep); }
                        total+=n;
                        if(total>=maxBytes) truncated[0]=true;
                    }
                } catch(Throwable ignored) {}
            },"DesktopGridX-RootReader");
            reader.start();
            boolean done=p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if(!done) p.destroyForcibly();
            reader.join(2000);
            String text;
            synchronized(out){ text=new String(out.toByteArray(),StandardCharsets.UTF_8); }
            if(truncated[0]) text += "\n[OUTPUT TRUNCATED]\n";
            if(!done) text += "\n[COMMAND TIMEOUT]\n";
            return new Result(done?p.exitValue():-1,text,!done);
        } catch(Throwable t) {
            if(p!=null) p.destroyForcibly();
            return new Result(-1,"ROOT_COMMAND_ERROR: "+t+"\n",false);
        }
    }
}
