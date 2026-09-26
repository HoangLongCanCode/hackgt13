// Pull the latest Android capture session from the phone into a local working directory.
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const REMOTE_BASE_CANDIDATES = [
  '/storage/emulated/0/Android/data/com.hackgt13.phase1collector/files/phase1',
  '/sdcard/Android/data/com.hackgt13.phase1collector/files/phase1',
];
const DEFAULT_OUTPUT_DIR = path.join(process.cwd(), 'captured', 'android');
const DEFAULT_ADB_PATH = path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');

function getAdbCommand() {
  if (process.env.ADB_PATH && fs.existsSync(process.env.ADB_PATH)) {
    return process.env.ADB_PATH;
  }

  if (fs.existsSync(DEFAULT_ADB_PATH)) {
    return DEFAULT_ADB_PATH;
  }

  return 'adb';
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options,
  });

  if (result.error) {
    throw result.error;
  }

  return result;
}

function ensureAdbAvailable() {
  const adbCommand = getAdbCommand();
  const result = run(adbCommand, ['version']);
  if (result.status !== 0) {
    throw new Error(result.stderr.trim() || `adb is not available: ${adbCommand}`);
  }

  return adbCommand;
}

function ensureDeviceConnected(adbCommand) {
  const result = run(adbCommand, ['devices']);
  if (result.status !== 0) {
    throw new Error(result.stderr.trim() || 'Failed to query adb devices');
  }

  const lines = result.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const devices = lines
    .slice(1)
    .map((line) => line.split(/\s+/))
    .filter((parts) => parts.length >= 2 && parts[1] === 'device')
    .map((parts) => parts[0]);

  if (devices.length === 0) {
    throw new Error('No authorized Android device found. Check USB debugging and accept the prompt on the phone.');
  }

  return devices[0];
}

function remotePathExists(adbCommand, remotePath) {
  const result = run(adbCommand, ['shell', 'sh', '-c', `ls '${remotePath}' >/dev/null 2>&1 && echo exists || echo missing`]);
  if (result.status !== 0) {
    throw new Error(result.stderr.trim() || `Failed to check remote path: ${remotePath}`);
  }

  return result.stdout.trim() === 'exists';
}

function listRemoteSessionDirs(adbCommand, remoteBase) {
  const result = run(adbCommand, ['shell', 'ls', '-1', remoteBase]);
  if (result.status !== 0) {
    return [];
  }

  return result.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((name) => name.startsWith('session_'));
}

function pullRemoteTree(adbCommand, remoteBase, outputDir) {
  fs.mkdirSync(outputDir, { recursive: true });

  const result = run(adbCommand, ['pull', remoteBase, outputDir]);
  if (result.status !== 0) {
    throw new Error(result.stderr.trim() || `Failed to pull ${remoteBase}`);
  }

  return result.stdout.trim();
}

function resolveRemoteBase(adbCommand) {
  for (const candidate of REMOTE_BASE_CANDIDATES) {
    if (remotePathExists(adbCommand, candidate)) {
      return candidate;
    }
  }

  return null;
}

function main() {
  const outputDir = path.resolve(process.argv[2] || DEFAULT_OUTPUT_DIR);

  const adbCommand = ensureAdbAvailable();
  ensureDeviceConnected(adbCommand);

  const remoteBase = resolveRemoteBase(adbCommand);
  if (!remoteBase) {
    throw new Error(`Remote capture directory not found. Tried: ${REMOTE_BASE_CANDIDATES.join(', ')}`);
  }

  const sessionDirs = listRemoteSessionDirs(adbCommand, remoteBase);
  if (sessionDirs.length === 0) {
    throw new Error(`No session folders found under ${remoteBase}`);
  }

  const pulled = pullRemoteTree(adbCommand, remoteBase, outputDir);

  process.stdout.write([
    `Pulled ${sessionDirs.length} session folder(s) from device.`,
    `Remote root: ${remoteBase}`,
    `Local output: ${outputDir}`,
    pulled ? `adb: ${pulled}` : '',
  ].filter(Boolean).join('\n') + '\n');
}

main();
