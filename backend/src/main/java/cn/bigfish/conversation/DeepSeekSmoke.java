package cn.bigfish.conversation;

import cn.bigfish.contract.*;
import java.io.PrintStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit standalone validation. Never run by the normal test suite or application startup. */
public final class DeepSeekSmoke {
    private DeepSeekSmoke() {}
    public static void main(String[] args) {
        int status=run(args,"true".equals(System.getenv("BIGFISH_ALLOW_PAID")),System.getenv("BIGFISH_DEEPSEEK_API_KEY"),System.out);
        if(status!=0) System.exit(status);
    }
    static int run(String[] args, boolean allowPaid, String key, PrintStream out) {
        String mode=null, model=null; int count=1;
        try {
            for(int i=0;i<args.length;i++) {
                switch(args[i]) {
                    case "--models", "--check", "--generate" -> { if(mode!=null) throw new IllegalArgumentException(); mode=args[i]; }
                    case "--model" -> { if(model!=null) throw new IllegalArgumentException(); model=args[++i]; }
                    case "--requests" -> count=Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException();
                }
            }
            if(mode==null || count<1 || count>3 || (!mode.equals("--generate") && count!=1)
                || (!mode.equals("--models") && (model==null || !model.matches("[A-Za-z0-9._:/-]{1,100}")))) throw new IllegalArgumentException();
        } catch(RuntimeException error) {
            out.println("Usage: --models | --check --model <discovered-id> | --generate --model <discovered-id> [--requests 1..3]"); return 2;
        }
        if(!allowPaid || key==null || key.isBlank()) { out.println("PROVIDER_DISABLED: configure BIGFISH_ALLOW_PAID and BIGFISH_DEEPSEEK_API_KEY in this process environment; no requests sent."); return 3; }
        int generated=0;
        try(var probe=DeepSeekConnectionProbe.create(allowPaid,key)) {
            var check=probe.check(UUID.randomUUID().toString(),model,Duration.ofSeconds(10)).toCompletableFuture().get(11,TimeUnit.SECONDS);
            out.printf("checkedAt=%s authentication=%s model=%s generation=%s code=%s elapsedMs=%d%n",Instant.now(),
                check.authentication(),check.selectedModel(),check.generation(),check.diagnostic().code(),check.diagnostic().elapsedMs());
            if(check.diagnostic().code()!=DeepSeekFailure.Code.SUCCESS) return 4;
            if(mode.equals("--models")) { out.println("availableModels="+check.availableModels()); return 0; }
            if(!mode.equals("--generate")) return 0;
            // Smoke tests never retry: at most three actual generation HTTP requests, not three retry groups.
            var policy=new DeepSeekConversation.Policy(Duration.ofSeconds(5),Duration.ZERO,1);
            try(var adapter=DeepSeekConversation.create(allowPaid,key,policy)) {
                String[] prompts={"请用一句话打招呼。","请用一句话欢迎观众。","请用一句话感谢观众的留言。"};
                var d=RuntimeSettings.defaults();
                var config=new ConfigSnapshot(1,Instant.now(),new RuntimeSettings(d.profileName(),"请用简短友好的中文回答，不超过30字。",
                    d.roomId(),model,128,d.voice(),false,d.welcomeIntervalSeconds(),d.synthesisQueueCapacity(),d.playbackQueueCapacity(),d.priorities()));
                for(int i=0;i<count;i++) {
                    String id=UUID.randomUUID().toString(); var chars=new AtomicInteger(); generated++;
                    var event=new LiveEvent(1,id,"standalone-validation","mock-room",LiveEvent.Type.COMMENT,"","",Instant.now(),Map.of("content",prompts[i]));
                    try {
                        adapter.reply(id,event,config,Duration.ofSeconds(20),text -> chars.set(text.length())).toCompletableFuture().get(21,TimeUnit.SECONDS);
                        out.printf("generation=PASSED model=%s request=%d elapsedMs=%d responseChars=%d%n",model,generated,adapter.diagnostic(id).orElseThrow().elapsedMs(),chars.get());
                    } catch(Exception error) {
                        var diagnostic=adapter.diagnostic(id).orElse(new DeepSeekFailure.Diagnostic(DeepSeekFailure.Code.NETWORK_ERROR,0,0,0));
                        out.printf("generation=FAILED model=%s request=%d code=%s elapsedMs=%d%n",model,generated,diagnostic.code(),diagnostic.elapsedMs()); return 5;
                    }
                }
            }
            return 0;
        } catch(Exception error) { out.println("VALIDATION_FAILED: no raw exception/body logged; generationRequests="+generated); return 6; }
    }
}
