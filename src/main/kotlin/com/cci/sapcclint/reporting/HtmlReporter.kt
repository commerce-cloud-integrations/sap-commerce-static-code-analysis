package com.cci.sapcclint.reporting

import com.cci.sapcclint.core.AnalysisResult
import com.cci.sapcclint.rules.Finding
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import java.nio.file.Path

class HtmlReporter {

    fun write(result: AnalysisResult, outputPath: Path) {
        outputPath.parent?.createDirectories()
        outputPath.writeText(render(result))
    }

    private fun render(result: AnalysisResult): String {
        val scannedDomains = result.scannedDomains()
        val findingsByRule = result.findings.groupingBy { it.ruleId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val findingsByDomain = result.findings.groupingBy { it.domain.cliValue }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>sapcc-lint report</title>
              <style>
                :root {
                  color-scheme: light;
                  --bg: #f6f8fb;
                  --panel: #ffffff;
                  --text: #172033;
                  --muted: #5a6480;
                  --border: #d8deea;
                  --accent: #1d5bd1;
                  --error: #b42318;
                  --warning: #b54708;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  background: linear-gradient(180deg, #f8fbff 0%, var(--bg) 100%);
                  color: var(--text);
                  font: 14px/1.5 "IBM Plex Sans", "Segoe UI", sans-serif;
                }
                main {
                  max-width: 1280px;
                  margin: 0 auto;
                  padding: 32px 24px 48px;
                }
                h1, h2 { margin: 0 0 12px; }
                p { margin: 0 0 12px; color: var(--muted); }
                section {
                  background: var(--panel);
                  border: 1px solid var(--border);
                  border-radius: 16px;
                  padding: 20px;
                  margin-top: 20px;
                  box-shadow: 0 10px 30px rgba(23, 32, 51, 0.05);
                }
                .summary {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                  gap: 12px;
                }
                .metric {
                  border: 1px solid var(--border);
                  border-radius: 12px;
                  padding: 14px 16px;
                  background: #fbfcff;
                }
                .metric .label {
                  display: block;
                  font-size: 12px;
                  text-transform: uppercase;
                  letter-spacing: 0.08em;
                  color: var(--muted);
                }
                .metric .value {
                  display: block;
                  margin-top: 6px;
                  font-size: 28px;
                  font-weight: 700;
                }
                .lists {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
                  gap: 16px;
                }
                ul {
                  margin: 0;
                  padding-left: 18px;
                }
                li + li { margin-top: 6px; }
                .toolbar {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  gap: 12px;
                  margin-bottom: 12px;
                  flex-wrap: wrap;
                }
                input[type="search"] {
                  width: min(100%, 360px);
                  padding: 10px 12px;
                  border: 1px solid var(--border);
                  border-radius: 10px;
                  font: inherit;
                }
                table {
                  width: 100%;
                  border-collapse: collapse;
                }
                th, td {
                  padding: 10px 12px;
                  border-top: 1px solid var(--border);
                  vertical-align: top;
                  text-align: left;
                }
                th {
                  cursor: pointer;
                  background: #fbfcff;
                  position: sticky;
                  top: 0;
                }
                .badge {
                  display: inline-block;
                  border-radius: 999px;
                  padding: 2px 8px;
                  font-size: 12px;
                  font-weight: 700;
                }
                .badge.ERROR { background: #fef3f2; color: var(--error); }
                .badge.WARNING { background: #fffaeb; color: var(--warning); }
                .mono { font-family: "IBM Plex Mono", "SFMono-Regular", monospace; }
                .muted { color: var(--muted); }
              </style>
            </head>
            <body>
              <main>
                <section>
                  <h1>sapcc-lint report</h1>
                  <p>Repository root: <span class="mono">${result.repo.toAbsolutePath().normalize().toString().escapeHtml()}</span></p>
                  <div class="summary">
                    <div class="metric"><span class="label">Scanned files</span><span class="value">${result.analyzedFileCount}</span></div>
                    <div class="metric"><span class="label">Domains</span><span class="value">${scannedDomains.size}</span></div>
                    <div class="metric"><span class="label">Findings</span><span class="value">${result.findings.size}</span></div>
                    <div class="metric"><span class="label">Errors</span><span class="value">${result.errorCount}</span></div>
                    <div class="metric"><span class="label">Warnings</span><span class="value">${result.warningCount}</span></div>
                  </div>
                </section>

                <section>
                  <h2>Findings by severity</h2>
                  <div class="summary">
                    <div class="metric"><span class="label">ERROR</span><span class="value">${result.errorCount}</span></div>
                    <div class="metric"><span class="label">WARNING</span><span class="value">${result.warningCount}</span></div>
                  </div>
                </section>

                <section>
                  <h2>Findings by domain</h2>
                  <div class="lists">
                    <div>
                      <ul>
                        ${renderCountList(findingsByDomain)}
                      </ul>
                    </div>
                  </div>
                </section>

                <section>
                  <h2>Findings by rule</h2>
                  <div class="lists">
                    <div>
                      <ul>
                        ${renderCountList(findingsByRule)}
                      </ul>
                    </div>
                  </div>
                </section>

                <section>
                  <h2>Scanned domains</h2>
                  <div class="lists">
                    <div>
                      <ul>
                        ${scannedDomains.joinToString("") {
                            "<li><span class=\"mono\">${it.domain.cliValue.escapeHtml()}</span> <span class=\"muted\">${it.analyzedFileCount} file(s)</span></li>"
                        }}
                      </ul>
                    </div>
                  </div>
                </section>

                <section>
                  <div class="toolbar">
                    <div>
                      <h2>Findings table</h2>
                      <p>Click a column header to sort. Use the filter box to narrow rows.</p>
                    </div>
                    <input id="filterInput" type="search" placeholder="Filter findings">
                  </div>
                  <table id="findingsTable">
                    <thead>
                      <tr>
                        <th>Severity</th>
                        <th>Domain</th>
                        <th>Rule ID</th>
                        <th>File</th>
                        <th>Line</th>
                        <th>Column</th>
                        <th>Message</th>
                        <th>Entity key</th>
                      </tr>
                    </thead>
                    <tbody>
                      ${renderFindingRows(result)}
                    </tbody>
                  </table>
                </section>
              </main>
              <script>
                const filterInput = document.getElementById("filterInput");
                const table = document.getElementById("findingsTable");
                const tbody = table.querySelector("tbody");
                const headers = Array.from(table.querySelectorAll("th"));
                let sortState = { index: 0, asc: true };

                filterInput.addEventListener("input", () => {
                  const query = filterInput.value.trim().toLowerCase();
                  Array.from(tbody.rows).forEach((row) => {
                    row.style.display = row.textContent.toLowerCase().includes(query) ? "" : "none";
                  });
                });

                headers.forEach((header, index) => {
                  header.addEventListener("click", () => {
                    sortState = {
                      index,
                      asc: sortState.index === index ? !sortState.asc : true,
                    };
                    const rows = Array.from(tbody.rows);
                    rows.sort((left, right) => compareCells(left.cells[index].dataset.sort || left.cells[index].textContent, right.cells[index].dataset.sort || right.cells[index].textContent, sortState.asc));
                    rows.forEach((row) => tbody.appendChild(row));
                  });
                });

                function compareCells(a, b, asc) {
                  const left = a.trim();
                  const right = b.trim();
                  const leftNumber = Number(left);
                  const rightNumber = Number(right);
                  const result = Number.isFinite(leftNumber) && Number.isFinite(rightNumber)
                    ? leftNumber - rightNumber
                    : left.localeCompare(right);
                  return asc ? result : -result;
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderCountList(entries: List<Map.Entry<String, Int>>): String {
        if (entries.isEmpty()) {
            return "<li>No findings.</li>"
        }
        return entries.joinToString("") { entry ->
            "<li><span class=\"mono\">${entry.key.escapeHtml()}</span> <span class=\"muted\">${entry.value}</span></li>"
        }
    }

    private fun renderFindingRows(result: AnalysisResult): String {
        if (result.findings.isEmpty()) {
            return "<tr><td colspan=\"8\">No findings.</td></tr>"
        }
        return result.findings.joinToString("") { finding ->
            renderFindingRow(result, finding)
        }
    }

    private fun renderFindingRow(result: AnalysisResult, finding: Finding): String {
        val relativePath = finding.relativePath(result.repo)
        val line = finding.location.position.line
        val column = finding.location.position.column
        return """
            <tr>
              <td data-sort="${finding.severity.name.escapeHtml()}"><span class="badge ${finding.severity.name}">${finding.severity.name.escapeHtml()}</span></td>
              <td data-sort="${finding.domain.cliValue.escapeHtml()}">${finding.domain.cliValue.escapeHtml()}</td>
              <td data-sort="${finding.ruleId.escapeHtml()}" class="mono">${finding.ruleId.escapeHtml()}</td>
              <td data-sort="${relativePath.escapeHtml()}" class="mono">${relativePath.escapeHtml()}</td>
              <td data-sort="$line">$line</td>
              <td data-sort="$column">$column</td>
              <td data-sort="${finding.message.escapeHtml()}">${finding.message.escapeHtml()}</td>
              <td data-sort="${finding.entityKey.orEmpty().escapeHtml()}" class="mono">${finding.entityKey.orEmpty().escapeHtml()}</td>
            </tr>
        """.trimIndent()
    }
}
