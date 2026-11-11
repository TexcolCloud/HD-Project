package cn.dormlink.mobility;

import java.util.*;

/** Shared entrance-to-entrance elapsed-time statistics; never divides minutes by device speed. */
public final class TravelLearning {
    private TravelLearning() {}
    public record Leg(String sessionId,int seconds) {}
    public record Model(int minutes,int sampleCount,int sessionCount) {}

    public static Optional<Model> learn(List<Leg> legs) {
        Map<String,Integer> contributed=new HashMap<>();
        List<Leg> bounded=new ArrayList<>();
        for (Leg leg:legs) {
            if (leg.seconds()<10 || leg.seconds()>1800 || contributed.getOrDefault(leg.sessionId(),0)>=2) { continue; }
            contributed.merge(leg.sessionId(),1,Integer::sum); bounded.add(leg);
        }
        if (bounded.size()<5 || contributed.size()<3) { return Optional.empty(); }
        int[] sorted=bounded.stream().mapToInt(Leg::seconds).sorted().toArray();
        int median=sorted[sorted.length/2];
        double tolerance=Math.max(15,median*0.5);
        List<Leg> accepted=bounded.stream().filter(leg -> Math.abs(leg.seconds()-median)<=tolerance).toList();
        int sessions=(int)accepted.stream().map(Leg::sessionId).distinct().count();
        if (accepted.size()<5 || sessions<3) { return Optional.empty(); }
        int[] robust=accepted.stream().mapToInt(Leg::seconds).sorted().toArray();
        return Optional.of(new Model((robust[robust.length/2]+59)/60,accepted.size(),sessions));
    }
}
