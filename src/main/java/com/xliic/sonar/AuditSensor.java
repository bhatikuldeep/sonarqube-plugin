package com.xliic.sonar;

import java.io.IOException;

import com.xliic.cicd.audit.AuditException;
import com.xliic.cicd.audit.Auditor;
import com.xliic.cicd.audit.model.assessment.AssessmentReport;
import com.xliic.cicd.audit.model.assessment.AssessmentReport.Issue;
import com.xliic.cicd.audit.model.assessment.AssessmentReport.Section;
import com.xliic.cicd.audit.model.assessment.AssessmentReport.SubIssue;
import com.xliic.openapi.bundler.Mapping;
import com.xliic.openapi.bundler.Mapping.Location;
import com.xliic.openapi.bundler.reverse.Document;
import com.xliic.openapi.bundler.reverse.Parser;
import com.xliic.sonar.ResultCollectorImpl.Result;

import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class AuditSensor implements Sensor {

    private WorkspaceImpl workspace;
    private static final Logger LOGGER = Loggers.get(AuditRulesDefinition.class);

    @Override
    public void describe(SensorDescriptor descriptor) {
        descriptor.name("REST API Static Security Testing");
    }

    private ResultCollectorImpl audit(WorkspaceImpl workspace) {
        LoggerImpl logger = new LoggerImpl();
        SecretImpl apiKey = new SecretImpl("851d8b6e-0584-4909-b5a8-a88528d8f81d");
        ResultCollectorImpl results = new ResultCollectorImpl();
        Auditor auditor = new Auditor(workspace, logger, apiKey);
        auditor.setResultCollector(results);
        try {
            auditor.audit(workspace, "sq", 0);
        } catch (IOException | InterruptedException | AuditException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return results;
    }

    void saveMeasures(SensorContext context, InputFile file, Result result) {
        int score = result.score;
        int security_score = result.report.security != null ? Math.round(result.report.security.score) : 0;
        int data_score = result.report.data != null ? Math.round(result.report.data.score) : 0;
        context.<Integer>newMeasure().withValue(score).forMetric(AuditMetrics.SCORE).on(file).save();
        context.<Integer>newMeasure().withValue(security_score).forMetric(AuditMetrics.SECURITY_SCORE).on(file).save();
        context.<Integer>newMeasure().withValue(data_score).forMetric(AuditMetrics.DATA_SCORE).on(file).save();
    }

    @Override
    public void execute(SensorContext context) {
        // TODO get extensions from properties
        FileSystem fs = context.fileSystem();
        FilePredicate mainFilePredicate = fs.predicates().or(fs.predicates().hasExtension("yaml"),
                fs.predicates().hasExtension("yml"), fs.predicates().hasExtension("json"));

        workspace = new WorkspaceImpl(context.fileSystem(), fs.inputFiles(mainFilePredicate));
        ResultCollectorImpl results = audit(workspace);

        for (String filename : results.results.keySet()) {
            InputFile inputFile = workspace.getInputFile(filename);
            Result result = results.get(filename);
            AssessmentReport report = result.report;

            saveMeasures(context, inputFile, result);

            try {
                saveIssues(context, report.index, result.mapping, report.data, inputFile, Severity.MAJOR);
                saveIssues(context, report.index, result.mapping, report.security, inputFile, Severity.MAJOR);
                saveIssues(context, report.index, result.mapping, report.semanticErrors, inputFile, Severity.CRITICAL);
                saveIssues(context, report.index, result.mapping, report.validationErrors, inputFile, Severity.BLOCKER);
                saveIssues(context, report.index, result.mapping, report.warnings, inputFile, Severity.INFO);
            } catch (IOException | InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

    }

    private Severity criticalityToSeverity(int criticality, Severity defaultSeverity) {
        switch (criticality) {
            case 1:
                return Severity.INFO;
            case 2:
                return Severity.MINOR;
            case 3:
                return Severity.MAJOR;
            case 4:
                return Severity.CRITICAL;
            case 5:
                return Severity.BLOCKER;
            default:
                return defaultSeverity;
        }
    }

    private int getLineByPointerIndex(InputFile file, Mapping mapping, String pointer)
            throws IOException, InterruptedException {
        Document document;
        Location location = mapping.find(pointer);
        LOGGER.info("get line {} {}", pointer, file);
        if (location == null) {
            // issue in the main file
            if (file.filename().toLowerCase().endsWith(".json")) {
                document = Parser.parseJson(file.contents());
            } else {
                document = Parser.parseYaml(file.contents());
            }
            return (int) document.getLine(pointer);
        } else if (location.file.toLowerCase().endsWith(".json")) {
            document = Parser.parseJson(workspace.read(location.file));
            return (int) document.getLine(location.pointer);
        } else {
            document = Parser.parseYaml(workspace.read(location.file));
            return (int) document.getLine(location.pointer);
        }
    }

    private void saveIssues(SensorContext context, String[] index, Mapping mapping, Section section,
            InputFile inputFile, Severity defaultSeverity) throws IOException, InterruptedException {
        if (section == null || section.issues == null) {
            return;
        }

        for (String id : section.issues.keySet()) {
            String issueId = id.toLowerCase().replace(".", "-");
            Issue issue = section.issues.get(id);
            for (SubIssue subIssue : issue.issues) {
                int line = getLineByPointerIndex(inputFile, mapping, index[subIssue.pointer]);
                NewIssue newIssue = context.newIssue();
                NewIssueLocation primaryLocation = newIssue.newLocation()
                        .message(
                                subIssue.specificDescription != null ? subIssue.specificDescription : issue.description)
                        .on(inputFile).at(inputFile.selectLine(line));
                RuleKey ruleKey = RuleKey.of(AuditPlugin.REPO_KEY, issueId);
                newIssue.forRule(ruleKey).at(primaryLocation);
                newIssue.overrideSeverity(criticalityToSeverity(issue.criticality, defaultSeverity));
                newIssue.save();
                LOGGER.info("issue {} {}", issueId, issue.criticality);

            }
        }
    }

}