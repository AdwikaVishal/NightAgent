module.exports = {
  rootDir: ".",
  testMatch: ["**/tests/rules/*.test.js"],
  testEnvironment: "node",
  testTimeout: 30000,
  collectCoverageFrom: ["tests/rules/*.test.js"],
  coverageDirectory: "coverage",
  verbose: true,
};
