// Error messages travel to the perception server's log and to every connected tablet: never include the API key.
function redact(url) {
  return String(url).replace(/([?&]key=)[^&#]*/gi, '$1REDACTED');
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} from ${redact(url)}`);
  }
  return response.json();
}

async function postJson(url, body, headers = {}) {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`HTTP ${response.status} from ${redact(url)}: ${text}`);
  }

  return response.json();
}

module.exports = {
  getJson,
  postJson,
  redact,
};
