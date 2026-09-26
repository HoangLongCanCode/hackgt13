// Generate a deterministic Phase 1 replay from local route lookup plus synthetic trip samples.
require('./load-env').loadEnv();
const { buildDemoTimelineFromSessionDir, ensureDemoSession } = require('./phase1-demo-lib');

async function main() {
  const sessionDir = await ensureDemoSession();
  const { session, frames } = await buildDemoTimelineFromSessionDir(sessionDir);

  process.stdout.write(
    `${JSON.stringify(
      {
        sessionId: session.manifest.sessionId,
        provider: session.route.provider,
        destination: session.route.destination,
        frames,
      },
      null,
      2
    )}\n`
  );
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
