package io.github.leohou0430.llmgw.control;

import io.github.leohou0430.llmgw.control.local.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AtomicRoutePublicationTest {
    @Test void candidateAndExpectedVersionAreOneOperation() throws Exception {
        var store=new InMemoryRouteVersionStore();var service=new RouteVersionService(store);
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(String config:List.of("request-A","request-B"))results.add(pool.submit(()->{start.await();try{service.publish(config,0);return true;}catch(VersionConflictException e){return false;}}));
            start.countDown();int published=0;for(var f:results)if(f.get())published++;
            assertEquals(1,published);assertEquals(store.latest().orElseThrow().config(),service.activeConfig());
        }
        assertThrows(VersionConflictException.class,()->store.append(new RouteVersion(1,"overwrite",0)));
    }
    @Test void validatesActualCandidateAndRecoversWithRealActivator() {
        var store=new InMemoryRouteVersionStore();var activated=new ArrayList<String>();
        var service=new RouteVersionService(store,config->{if(!config.startsWith("valid:"))throw new IllegalArgumentException("invalid");},v->activated.add(v.config()));
        assertThrows(IllegalArgumentException.class,()->service.publish("not JSON",0));assertTrue(store.latest().isEmpty());
        service.publish("valid:A",0);service.publish("valid:B",1);service.rollback(1,2);
        assertEquals(List.of("valid:A","valid:B","valid:A"),activated);
        var recovered=new RouteVersionService(store,config->{},v->activated.add(v.config()));
        assertEquals(3,recovered.activeVersion());assertEquals("valid:A",activated.getLast());
    }
}
