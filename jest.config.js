module.exports = {
  rootDir: ".",
  testMatch: ["**/tests/rules/*.test.js", "**/tests/live-tracker/*.test.js"],
  testEnvironment: "node",
  testTimeout: 30000,
  collectCoverageFrom: ["tests/rules/*.test.js", "tests/live-tracker/*.test.js"],
  coverageDirectory: "coverage",
  verbose: true,
};
