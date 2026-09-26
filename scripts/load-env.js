// Load local environment variables from .env without printing or exporting secrets anywhere else.
const fs = require('fs');
const path = require('path');

function parseEnvFile(content) {
  const entries = {};

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }

    const equalsIndex = line.indexOf('=');
    if (equalsIndex === -1) {
      continue;
    }

    const key = line.slice(0, equalsIndex).trim();
    let value = line.slice(equalsIndex + 1).trim();

    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }

    entries[key] = value;
  }

  return entries;
}

function loadEnv(envPath = path.join(process.cwd(), '.env')) {
  if (!fs.existsSync(envPath)) {
    return {};
  }

  const parsed = parseEnvFile(fs.readFileSync(envPath, 'utf8'));
  for (const [key, value] of Object.entries(parsed)) {
    if (process.env[key] == null || process.env[key] === '') {
      process.env[key] = value;
    }
  }

  return parsed;
}

module.exports = {
  loadEnv,
};
