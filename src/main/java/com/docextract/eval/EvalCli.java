package com.docextract.eval;

import com.docextract.DocextractApplication;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.eval.corpus.CorpusLoader;
import com.docextract.eval.corpus.LabeledDocument;
import com.docextract.service.RoutedStrategy;
import com.docextract.service.TierRouter;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.validation.ValidationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Usage: java -jar docextract.jar eval [--corpus=corpus] [--strategy=all|name] [--out=eval-results]
 * Boots the same Spring context as the API (without a web server) so strategies are identical.
 * "all" runs every tier plus "auto-routed" (the API's default routing).
 */
public final class EvalCli {

    private EvalCli() {
    }

    public static int run(String[] args) throws Exception {
        String corpus = arg(args, "corpus", "corpus");
        String strategyName = arg(args, "strategy", "all");
        Path outDir = Path.of(arg(args, "out", "eval-results"));

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DocextractApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .properties("logging.level.root=WARN")
                .run()) {

            ObjectMapper json = ctx.getBean(ObjectMapper.class);
            List<ExtractionStrategy> available = new ArrayList<>(ctx.getBeansOfType(ExtractionStrategy.class).values());
            available.sort(Comparator.comparing(ExtractionStrategy::costTier));
            available.add(new RoutedStrategy(ctx.getBean(TierRouter.class), ctx.getBean(DocumentTypeDetector.class)));
            List<ExtractionStrategy> strategies = available.stream()
                    .filter(s -> strategyName.equals("all") || s.name().equals(strategyName))
                    .toList();
            if (strategies.isEmpty()) {
                System.err.println("No strategy named '" + strategyName + "'. Available: "
                        + available.stream().map(ExtractionStrategy::name).toList());
                return 2;
            }

            List<LabeledDocument> docs = new CorpusLoader(json).load(Path.of(corpus));
            System.out.println("Loaded " + docs.size() + " labelled documents from " + Path.of(corpus).toAbsolutePath());

            Evaluator evaluator = new Evaluator(ctx.getBean(ValidationEngine.class), json);
            List<EvaluationReport> reports = new ArrayList<>();
            Map<String, String> notConfigured = new LinkedHashMap<>();
            for (ExtractionStrategy s : strategies) {
                Optional<String> reason = s.notConfiguredReason();
                if (reason.isPresent()) {
                    notConfigured.put(s.name(), reason.get());
                    System.out.println();
                    System.out.println("=== Strategy: " + s.name() + " === NOT RUN: " + reason.get());
                    continue;
                }
                EvaluationReport report = evaluator.evaluate(s, docs);
                ReportWriter.printConsole(report, System.out);
                reports.add(report);
            }
            ReportWriter.printComparison(reports, notConfigured, System.out);

            Files.createDirectories(outDir);
            Path fields = outDir.resolve("fields.csv");
            Path documents = outDir.resolve("documents.csv");
            Path tiers = outDir.resolve("tiers.csv");
            ReportWriter.writeCsv(reports, fields, documents);
            ReportWriter.writeTierCsv(reports, notConfigured, tiers);
            System.out.println();
            System.out.println("CSV written: " + fields + ", " + documents + ", " + tiers);
            return 0;
        }
    }

    private static String arg(String[] args, String name, String fallback) {
        String prefix = "--" + name + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return fallback;
    }
}
