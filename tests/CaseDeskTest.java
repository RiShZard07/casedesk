import java.net.URI;
import java.net.http.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free regression and local HTTP integration tests; failures exit nonzero. */
public final class CaseDeskTest {
    private static int passed;
    private static void check(boolean condition) { if(!condition) throw new AssertionError("check failed"); }
    private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS "+name); }
    private static void problem(int status, Runnable action) {
        try { action.run(); throw new AssertionError("expected "+status); }
        catch(CaseDesk.Problem e) { check(e.status==status); }
    }
    private static HttpResponse<String> request(CaseDesk app,String method,String path,String content,String type) {
        try {
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+app.port()+path)).timeout(Duration.ofSeconds(5));
            if(type!=null)b.header("Content-Type",type);
            b.method(method,HttpRequest.BodyPublishers.ofString(content));
            return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    public static void main(String[] args) throws Exception {
        test("priority before arrival",()->{var s=new CaseDesk.Store();s.create("a","normal",1);s.create("b","urgent",5);check(s.claim().id()==2);});
        test("FIFO tie break",()->{var s=new CaseDesk.Store();s.create("a","first",3);s.create("b","second",3);check(s.claim().id()==1);check(s.claim().id()==2);});
        test("idempotent retry",()->{var s=new CaseDesk.Store();var a=s.create("a","same",2);check(s.create("a","same",2).id()==a.id());check(s.snapshot().size()==1);});
        test("conflicting idempotency key",()->{var s=new CaseDesk.Store();s.create("a","original",2);problem(409,()->s.create("a","changed",2));check(s.snapshot().size()==1);});
        test("input boundaries",()->{var s=new CaseDesk.Store();problem(400,()->s.create("","ok",1));problem(400,()->s.create("a"," ",1));problem(400,()->s.create("a","x".repeat(161),1));problem(400,()->s.create("a","ok",0));problem(400,()->s.create("a","ok",6));check(s.snapshot().isEmpty());});
        test("state transition guards",()->{var s=new CaseDesk.Store();s.create("a","work",1);problem(409,()->s.close(1));check(s.claim().state()==CaseDesk.State.ASSIGNED);check(s.close(1).state()==CaseDesk.State.CLOSED);problem(409,()->s.close(1));problem(404,()->s.close(9));problem(404,s::claim);});
        test("retry returns current ticket state",()->{var s=new CaseDesk.Store();s.create("a","work",1);s.claim();s.close(1);check(s.create("a","work",1).state()==CaseDesk.State.CLOSED);});
        test("immutable snapshot",()->{var s=new CaseDesk.Store();s.create("a","work",1);var before=s.snapshot();s.claim();check(before.get(0).state()==CaseDesk.State.OPEN);try{before.clear();throw new AssertionError();}catch(UnsupportedOperationException expected){}});
        test("concurrent retry has one ticket",()->{
            var s=new CaseDesk.Store();ExecutorService pool=Executors.newFixedThreadPool(8);
            try {var calls=new ArrayList<Callable<Long>>();for(int i=0;i<100;i++)calls.add(()->s.create("shared","retry",3).id());for(var f:pool.invokeAll(calls))check(f.get()==1);check(s.snapshot().size()==1);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
        });
        test("concurrent claims never duplicate",()->{
            var s=new CaseDesk.Store();for(int i=0;i<100;i++)s.create("key"+i,"ticket"+i,1+i%5);
            ExecutorService pool=Executors.newFixedThreadPool(8);
            try{var calls=new ArrayList<Callable<Long>>();for(int i=0;i<100;i++)calls.add(()->s.claim().id());var ids=new HashSet<Long>();for(var f:pool.invokeAll(calls))check(ids.add(f.get()));check(ids.size()==100);problem(404,s::claim);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
        });
        test("JSON escaping",()->check(CaseDesk.quote("x\n\"\\\t").equals("\"x\\u000a\\\"\\\\\\u0009\"")));
        Path dir=Files.createTempDirectory("casedesk-journal");
        test("journal survives restart",()->{
            Path j=dir.resolve("restart.jsonl");
            try(var s=CaseDesk.Store.open(j)){s.create("a","first",3);s.create("b","urgent \"quoted\"\tx",5);s.create("c","third",3);check(s.claim().id()==2);s.close(2);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){
                var all=s.snapshot();check(all.size()==3);
                check(all.get(1).id()==2&&all.get(1).state()==CaseDesk.State.CLOSED&&all.get(1).title().equals("urgent \"quoted\"\tx"));
                check(all.get(0).state()==CaseDesk.State.OPEN&&all.get(2).state()==CaseDesk.State.OPEN);
                check(s.create("d","new",1).id()==4);
            }catch(IOException e){throw new RuntimeException(e);}
        });
        test("idempotency key survives restart",()->{
            Path j=dir.resolve("keys.jsonl");
            try(var s=CaseDesk.Store.open(j)){s.create("same","work",2);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(s.create("same","work",2).id()==1);problem(409,()->s.create("same","changed",2));check(s.snapshot().size()==1);}catch(IOException e){throw new RuntimeException(e);}
        });
        test("priority order survives restart",()->{
            Path j=dir.resolve("order.jsonl");
            try(var s=CaseDesk.Store.open(j)){s.create("a","low",1);s.create("b","top",5);s.create("c","mid1",3);s.create("d","mid2",3);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(s.claim().id()==2);check(s.claim().id()==3);check(s.claim().id()==4);check(s.claim().id()==1);problem(404,s::claim);}catch(IOException e){throw new RuntimeException(e);}
        });
        test("torn tail line is dropped and repaired",()->{
            Path j=dir.resolve("torn.jsonl");
            try(var s=CaseDesk.Store.open(j)){s.create("a","kept",2);}catch(IOException e){throw new RuntimeException(e);}
            try{Files.writeString(j,"{\"op\":\"create\",\"id\":2,\"key\":\"b\",\"ti",java.nio.file.StandardOpenOption.APPEND);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(s.snapshot().size()==1);check(s.create("b","after crash",4).id()==2);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(s.snapshot().size()==2);check(s.snapshot().get(1).title().equals("after crash"));}catch(IOException e){throw new RuntimeException(e);}
        });
        test("corrupt middle line fails fast",()->{
            Path j=dir.resolve("corrupt.jsonl");
            try{Files.writeString(j,"{\"op\":\"create\",\"id\":1,\"key\":\"a\",\"title\":\"ok\",\"priority\":1}\nnot json\n{\"op\":\"claim\",\"id\":1}\n");}catch(IOException e){throw new RuntimeException(e);}
            try{CaseDesk.Store.open(j).close();throw new AssertionError("expected startup failure");}catch(IOException expected){check(expected.getMessage().contains("line 2"));}
            Path k=dir.resolve("inconsistent.jsonl");
            try{Files.writeString(k,"{\"op\":\"close\",\"id\":7}\n");}catch(IOException e){throw new RuntimeException(e);}
            try{CaseDesk.Store.open(k).close();throw new AssertionError("expected startup failure");}catch(IOException expected){check(expected.getMessage().contains("inconsistent"));}
        });
        test("failed journal write leaves state unchanged",()->{
            Path j=dir.resolve("failing.jsonl");
            class Failing extends Journal{boolean fail;Failing()throws IOException{super(j);}@Override public void append(Event e)throws IOException{if(fail)throw new IOException("disk full");super.append(e);}}
            try{var journal=new Failing();try(var s=new CaseDesk.Store(journal)){
                s.create("a","durable",3);journal.fail=true;
                problem(503,()->s.create("b","lost",1));check(s.snapshot().size()==1);
                problem(503,s::claim);check(s.snapshot().get(0).state()==CaseDesk.State.OPEN);
                journal.fail=false;check(s.claim().state()==CaseDesk.State.ASSIGNED);
            }}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(s.snapshot().size()==1);check(s.snapshot().get(0).state()==CaseDesk.State.ASSIGNED);}catch(IOException e){throw new RuntimeException(e);}
        });
        test("startup compaction bounds the journal",()->{
            Path j=dir.resolve("compact.jsonl");
            try(var s=CaseDesk.Store.open(j)){for(int i=0;i<20;i++)s.create("k"+i,"t"+i,1+i%5);for(int i=0;i<10;i++)s.claim();for(int i=0;i<5;i++)s.close(s.snapshot(CaseDesk.State.ASSIGNED).get(0).id());}catch(IOException e){throw new RuntimeException(e);}
            try{check(Files.readAllLines(j).size()==35);}catch(IOException e){throw new RuntimeException(e);}
            try(var s=CaseDesk.Store.open(j)){check(Files.readAllLines(j).size()==20);check(Files.readAllLines(j).stream().allMatch(l->l.contains("\"snapshot\"")));check(s.snapshot(CaseDesk.State.OPEN).size()==10&&s.snapshot(CaseDesk.State.ASSIGNED).size()==5&&s.snapshot(CaseDesk.State.CLOSED).size()==5);}catch(IOException e){throw new RuntimeException(e);}
        });
        try(var app=new CaseDesk(new CaseDesk.Store(),0,Path.of("web/index.html"))){
            test("HTTP create claim close",()->{check(request(app,"POST","/tickets","key=one&title=demo&priority=5","application/x-www-form-urlencoded").statusCode()==200);check(request(app,"POST","/claim","","application/x-www-form-urlencoded").body().contains("ASSIGNED"));check(request(app,"POST","/close","id=1","application/x-www-form-urlencoded").body().contains("CLOSED"));check(request(app,"GET","/tickets","",null).body().contains("demo"));});
            test("HTTP validation errors",()->{check(request(app,"POST","/tickets","key=x&title=y&priority=no","application/x-www-form-urlencoded").statusCode()==400);check(request(app,"POST","/tickets","{}","application/json").statusCode()==415);check(request(app,"POST","/close","id=1&id=2","application/x-www-form-urlencoded").statusCode()==400);check(request(app,"POST","/tickets","key=%ZZ","application/x-www-form-urlencoded").statusCode()==400);check(request(app,"POST","/tickets","x".repeat(8193),"application/x-www-form-urlencoded").statusCode()==413);});
            test("HTTP routes and methods",()->{check(request(app,"GET","/","",null).body().contains("CaseDesk"));check(request(app,"GET","/missing","",null).statusCode()==404);var wrong=request(app,"GET","/claim","",null);check(wrong.statusCode()==405);check(wrong.headers().firstValue("Allow").orElse("").equals("POST"));});
            test("HTTP metrics track state and errors",()->{String result=request(app,"GET","/metrics","",null).body();check(result.contains("\"closed\":1"));check(result.contains("\"open\":0"));check(result.contains("\"errors\":7"));check(result.contains("completed_request_nanos"));});
            test("HTTP state filter on /tickets",()->{check(request(app,"GET","/tickets?state=closed","",null).body().contains("demo"));check(request(app,"GET","/tickets?state=OPEN","",null).body().equals("[]"));check(request(app,"GET","/tickets?state=bogus","",null).statusCode()==400);});
            test("HTTP serves bundled React script",()->{var response=request(app,"GET","/app.js","",null);check(response.statusCode()==200);check(response.headers().firstValue("Content-Type").orElse("").contains("text/javascript"));check(response.body().length()>1000);});
        }
        System.out.println(passed+" test groups passed.");
    }
}
