package dev.ratelimiter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

class AggregateCoverageGateTest {
  private static final long REQUIRED_LINE_PERCENT = 80;

  @Test
  void criticalCoreAndStarterLineCoverageStaysAtOrAboveEightyPercent() throws Exception {
    Path report = Path.of(System.getProperty("aggregateCoverageXml"));
    assertTrue(Files.isRegularFile(report), () -> "Missing aggregate JaCoCo XML: " + report);

    DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
    documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    documents.setFeature("http://xml.org/sax/features/external-general-entities", false);
    documents.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    documents.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    documents.setExpandEntityReferences(false);
    documents.setXIncludeAware(false);
    documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Document document = documents.newDocumentBuilder().parse(report.toFile());

    Coverage line = coverage(document, "LINE");
    Coverage branch = coverage(document, "BRANCH");
    System.out.printf(
        "Aggregate critical coverage: line=%.2f%% (%d/%d), branch=%.2f%% (%d/%d)%n",
        line.percent(),
        line.covered(),
        line.total(),
        branch.percent(),
        branch.covered(),
        branch.total());

    assertTrue(line.total() > 0, "Aggregate JaCoCo report contains no executable lines");
    assertTrue(
        line.covered() * 100 >= line.total() * REQUIRED_LINE_PERCENT,
        () ->
            "Aggregate critical line coverage is %.2f%%; required minimum is %d%%"
                .formatted(line.percent(), REQUIRED_LINE_PERCENT));
  }

  private static Coverage coverage(Document document, String type) throws Exception {
    var xpath = XPathFactory.newInstance().newXPath();
    Node counter =
        (Node)
            xpath.evaluate("/report/counter[@type='" + type + "']", document, XPathConstants.NODE);
    assertTrue(counter != null, () -> "Aggregate JaCoCo report has no " + type + " counter");
    long covered = Long.parseLong(counter.getAttributes().getNamedItem("covered").getNodeValue());
    long missed = Long.parseLong(counter.getAttributes().getNamedItem("missed").getNodeValue());
    return new Coverage(covered, missed);
  }

  private record Coverage(long covered, long missed) {
    long total() {
      return covered + missed;
    }

    double percent() {
      return total() == 0 ? 0 : covered * 100.0D / total();
    }
  }
}
