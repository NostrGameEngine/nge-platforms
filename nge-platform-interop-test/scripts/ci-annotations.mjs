function escapeAnnotationData(value) {
  return String(value ?? '')
    .replace(/%/g, '%25')
    .replace(/\r/g, '%0D')
    .replace(/\n/g, '%0A');
}

function escapeAnnotationProperty(value) {
  return escapeAnnotationData(value)
    .replace(/:/g, '%3A')
    .replace(/,/g, '%2C');
}

export function emitInteropAnnotation(title, ok, description) {
  if (process.env.GITHUB_ACTIONS !== 'true') {
    return;
  }
  const command = ok ? 'notice' : 'error';
  process.stdout.write(`::${command} title=${escapeAnnotationProperty(title)}::${escapeAnnotationData(description)}\n`);
}

export function firstFailureText(...values) {
  for (const value of values) {
    if (value == null) {
      continue;
    }
    const text = String(value).trim();
    if (text) {
      return text.split('\n')[0].slice(0, 500);
    }
  }
  return 'No detailed failure message was reported.';
}
