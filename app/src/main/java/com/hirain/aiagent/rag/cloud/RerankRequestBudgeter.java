package com.hirain.aiagent.rag.cloud;

import java.util.ArrayList;
import java.util.List;

/** 独立 Rerank 字符预算；超预算候选从尾部截断，绝不挤占最终 Evidence 预算。 */
public final class RerankRequestBudgeter {
    private final int maxCharacters;
    public RerankRequestBudgeter(int maxCharacters) { this.maxCharacters=maxCharacters; }
    public List<RerankCandidate> apply(List<RerankCandidate> candidates) { List<RerankCandidate> output=new ArrayList<>();int used=0;for(RerankCandidate candidate:candidates){String heading=candidate.headingPath()==null?"":candidate.headingPath();String text=candidate.text()==null?"":candidate.text();int remaining=maxCharacters-used-heading.length();if(remaining<=0)break;String limited=text.substring(0,Math.min(text.length(),remaining));output.add(new RerankCandidate(heading,limited));used+=heading.length()+limited.length();}return List.copyOf(output); }
}
