const mock = require('./mock');
const google = require('./google');

function createRouteProvider(options = {}) {
  const providerName = options.providerName || process.env.PHASE1_ROUTE_PROVIDER || 'mock';

  if (providerName === 'google') {
    return google;
  }

  return mock;
}

module.exports = {
  createRouteProvider,
  mock,
  google,
};
