import fs from 'node:fs';
import path from 'node:path';

const [inputDir, outputFile] = process.argv.slice(2);

if (!inputDir || !outputFile) {
  process.stderr.write('Usage: node .github/scripts/merge-sarif-runs.mjs <input-dir> <output-file>\n');
  process.exit(2);
}

const resolvedOutput = path.resolve(outputFile);
const sarifFiles = fs
  .readdirSync(inputDir)
  .filter((name) => name.endsWith('.sarif'))
  .map((name) => path.resolve(inputDir, name))
  .filter((file) => file !== resolvedOutput)
  .sort();

if (sarifFiles.length === 0) {
  process.stderr.write(`No SARIF files found in ${inputDir}\n`);
  process.exit(1);
}

const merged = {
  version: '2.1.0',
  $schema: 'https://json.schemastore.org/sarif-2.1.0.json',
  runs: [
    {
      tool: {
        driver: {
          name: 'SpotBugs',
          informationUri: 'https://spotbugs.github.io/',
          rules: [],
        },
      },
      results: [],
      invocations: [],
      originalUriBaseIds: {},
      taxonomies: [],
    },
  ],
};

const mergedRun = merged.runs[0];
const ruleIndexes = new Map();
const taxonomyKeys = new Set();

function ruleKey(rule) {
  return rule?.id || rule?.name || JSON.stringify(rule);
}

function addRule(rule) {
  const key = ruleKey(rule);
  if (ruleIndexes.has(key)) return ruleIndexes.get(key);
  const index = mergedRun.tool.driver.rules.length;
  mergedRun.tool.driver.rules.push(rule);
  ruleIndexes.set(key, index);
  return index;
}

for (const file of sarifFiles) {
  const sarif = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const run of sarif.runs || []) {
    const rules = run.tool?.driver?.rules || [];
    for (const invocation of run.invocations || []) {
      mergedRun.invocations.push(invocation);
    }
    Object.assign(mergedRun.originalUriBaseIds, run.originalUriBaseIds || {});
    for (const taxonomy of run.taxonomies || []) {
      const key = taxonomy.name || JSON.stringify(taxonomy);
      if (!taxonomyKeys.has(key)) {
        mergedRun.taxonomies.push(taxonomy);
        taxonomyKeys.add(key);
      }
    }
    for (const result of run.results || []) {
      const copied = structuredClone(result);
      const rule = rules[result.ruleIndex] || rules.find((candidate) => candidate.id === result.ruleId);
      if (rule) {
        copied.ruleIndex = addRule(rule);
        copied.ruleId = copied.ruleId || rule.id;
      }
      mergedRun.results.push(copied);
    }
  }
}

fs.mkdirSync(path.dirname(outputFile), { recursive: true });
fs.writeFileSync(outputFile, `${JSON.stringify(merged, null, 2)}\n`);
console.log(`Merged ${sarifFiles.length} SARIF files into ${outputFile}`);
