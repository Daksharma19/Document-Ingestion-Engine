package com.docextract.eval;

import com.docextract.DocextractApplication;
import com.docextract.eval.corpus.CorpusLoader;
import com.docextract.eval.corpus.LabeledDocument;
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
import java.util.List;

/**
 * Usage: java -jar docextract.jar eval [--corpus=corpus] [--strategy=all|name] [--out=eval-results]
 * Boots the same Spring context as the API (without a web server) so strategies are identical.
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
            List<ExtractionStrategy> strategies = ctx.getBeansOfType(ExtractionStrategy.class).values().stream()
                    .filter(s -> strategyName.equals("all") || s.name().equals(strategyName))
                    .sorted(Comparator.comparing(ExtractionStrategy::costTier))
                    .toList();
            if (strategies.isEmpty()) {
                System.err.println("No strategy named '" + strategyName + "'. Available: "
                        + ctx.getBeansOfType(ExtractionStrategy.class).values().stream().map(ExtractionStrategy::name).toList());
                return 2;
            }

            List<LabeledDocument> docs = new CorpusLoader(json).load(Path.of(corpus));
            System.out.println("Loaded " + docs.size() + " labelled documents from " + Path.of(corpus).toAbsolutePath());

            Evaluator evaluator = new Evaluator(ctx.getBean(ValidationEngine.class), json);
            List<EvaluationReport> reports = new ArrayList<>();
            for (ExtractionStrategy s : strategies) {
                EvaluationReport report = evaluator.evaluate(s, docs);
                ReportWriter.printConsole(report, System.out);
                reports.add(report);
            }

            Files.createDirectories(outDir);
            Path fields = outDir.resolve("fields.csv");
            Path documents = outDir.resolve("documents.csv");
            ReportWriter.writeCsv(reports, fields, documents);
            System.out.println();
            System.out.println("CSV written: " + fields + ", " + documents);
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
