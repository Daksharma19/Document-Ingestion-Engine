package com.docextract.parse.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page: the method that produced its blocks and why it was chosen. {@code pending} is true when the planner
 * chose a method this build cannot run yet (or that is not configured), in which case {@code blocks} is empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedPage(int index, PageMethod method, String reason, boolean pending, Double confidence,
                         int costUnits, PageProbe probe, List<Block> blocks) {
}
