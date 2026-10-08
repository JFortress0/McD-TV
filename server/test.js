// Quick self-test: node test.js  (starts a throwaway server and exercises every route)
"use strict";
const os = require("node:os");
const path = require("node:path");
const fs = require("node:fs");
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "mcdtv-"));
process.env.INVITE_CODE = "letmein";
const { server } = require("./server.js");
const assert = require("node:assert");

server.listen(0, async () => {
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, p, body, token) => {
    const r = await fetch(base + p, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: r.status, json: await r.json() };
  };
  try {
    assert.equal((await call("GET", "/health")).json.ok, true);
    assert.equal((await call("POST", "/api/register", { username: "j", password: "secret1", invite: "letmein" })).status, 400);
    assert.equal((await call("POST", "/api/register", { username: "jmcd", password: "secret1" })).status, 403);
    const reg = await call("POST", "/api/register", { username: "JMcD", password: "secret1", invite: "letmein" });
    assert.equal(reg.status, 200);
    assert.equal((await call("POST", "/api/register", { username: "jmcd", password: "secret1", invite: "letmein" })).status, 409);
    assert.equal((await call("POST", "/api/login", { username: "jmcd", password: "wrong!!" })).status, 401);
    const login = await call("POST", "/api/login", { username: "jmcd", password: "secret1" });
    assert.equal(login.status, 200);
    const t = login.json.token;
    assert.equal((await call("GET", "/api/me", null, t)).json.username, "jmcd");
    assert.equal((await call("GET", "/api/me", null, "nope")).status, 401);
    assert.equal((await call("PUT", "/api/sync", { data: { watchlist: [1, 2] }, updatedAt: 1000 }, t)).status, 200);
    assert.equal((await call("PUT", "/api/sync", { data: { watchlist: [] }, updatedAt: 500 }, t)).status, 409);
    assert.deepEqual((await call("GET", "/api/sync", null, t)).json.data, { watchlist: [1, 2] });
    assert.equal((await call("POST", "/api/password", { old: "secret1", new: "secret2" }, t)).status, 200);
    assert.equal((await call("POST", "/api/login", { username: "jmcd", password: "secret2" })).status, 200);
    const pr = await call("POST", "/api/pair/start");
    assert.equal(pr.status, 200);
    assert.equal((await call("GET", "/api/pair/poll?id=" + pr.json.id)).status, 202);
    assert.equal((await call("POST", "/api/pair/approve", { code: "000000" }, t)).status, 404);
    assert.equal((await call("POST", "/api/pair/approve", { code: pr.json.code }, t)).status, 200);
    const done = await call("GET", "/api/pair/poll?id=" + pr.json.id);
    assert.equal(done.status, 200);
    assert.equal((await call("GET", "/api/me", null, done.json.token)).json.username, "jmcd");
    assert.equal((await call("POST", "/api/logout", null, t)).status, 200);
    assert.equal((await call("GET", "/api/me", null, t)).status, 401);
    console.log("ALL TESTS PASSED");
  } catch (e) {
    console.error("TEST FAILED:", e);
    process.exitCode = 1;
  } finally {
    server.close();
  }
});
