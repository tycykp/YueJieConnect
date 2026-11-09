package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** A remote source makes the entire transfer depend on that source; destinations are independent otherwise. */
final class XiaomiCirculateSelection {
    private XiaomiCirculateSelection() {}
    record Selection<T>(List<T> nativeTargets,List<T> rejected,boolean intercepted) {}
    static <T> Selection<T> plan(List<T> sources,List<T> targets,Predicate<T> registered){
        List<T> ownedSources=new ArrayList<>();
        if(sources!=null)for(T source:sources)if(registered.test(source))ownedSources.add(source);
        if(!ownedSources.isEmpty())return new Selection<>(List.of(),new ArrayList<>(targets.isEmpty()?ownedSources:targets),true);
        List<T> nativeTargets=new ArrayList<>(),rejected=new ArrayList<>();
        for(T target:targets){if(registered.test(target))rejected.add(target);else nativeTargets.add(target);}
        return new Selection<>(nativeTargets,rejected,!rejected.isEmpty());
    }
}
