import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { stripTypeScriptTypes } from "node:module";

async function load(path, bindings = {}) {
  const raw = await readFile(
    new URL("../entry/src/main/ets/" + path, import.meta.url),
    "utf8",
  );
  const exported = [...raw.matchAll(/^export (?:class|function) (\w+)/gm)].map(
    (match) => match[1],
  );
  const source = stripTypeScriptTypes(
    raw
      .replace(/^import[\s\S]*?;[ \t]*(?:\r?\n|$)/gm, "")
      .replace(/^@Observed\s*$/gm, ""),
  ).replace(/^export /gm, "");
  return new Function(
    ...Object.keys(bindings),
    source + "\nreturn {" + exported.join(",") + "};",
  )(...Object.values(bindings));
}
async function controller(Api, effects = {}, photos = {}) {
  const { ReadScope } = await load("features/ReadScope.ets", { Api });
  const { RepairController } = await load(
    "features/repairs/RepairController.ets",
    {
      Api,
      ReadScope,
      util: { generateRandomUUID: () => "key" },
      SubmissionKeys: class {
        clear() {}
        complete() {}
        reserve() {
          return "key";
        }
      },
      VisitWindowDraft: class {
        range() {
          return null;
        }
      },
    },
  );
  return new RepairController(
    {
      setError() {},
      flash() {},
      navigate() {},
      openRepair: async () => {},
      fail: (error) => {
        throw error;
      },
      ...effects,
    },
    photos,
  );
}

test('a confirmed duplicate visitor challenge can advance with a new request signature', async () => {
  const requests = [];
  const Api = { epoch: 1, assertCurrent() {}, post: async (_path, request) => {
    requests.push(request);
    if (!request.confirmDuplicate) throw Object.assign(new Error('duplicate'), {status: 409, code: 'VISITOR_DUPLICATE_CONFIRMATION_REQUIRED'});
    return {id: 'created'};
  }};
  const { ReadScope } = await load('features/ReadScope.ets', { Api });
  const { SubmissionKeys } = await load('services/SubmissionKeys.ets');
  const { LocalDrafts } = await load('services/LocalDrafts.ets');
  const { VisitorController } = await load('features/visitors/VisitorController.ets', { Api, ReadScope, SubmissionKeys, util: {generateRandomUUID: () => 'key'} });
  const data = new Map();
  const drafts = new LocalDrafts({get: key => data.get(key) || '', put: (key, value) => data.set(key, value), remove: key => data.delete(key)}, 'server', 'user');
  const host = {setError() {}, flash() {}, navigate() {}, confirm: async () => true, fail: e => { throw e; }};
  const visitors = new VisitorController(host);
  visitors.bindDrafts(drafts);
  Object.assign(visitors.state, {visitorName: '虚构访客', visitorPhone: '13800000000', visitedStudentId: '1', visitorPurpose: '测试探访'});
  await visitors.submitVisitor();
  assert.equal(requests.length, 2);
  assert.equal(requests[1].confirmDuplicate, true);
  assert.equal(drafts.hasPending('visitor'), false);
});

test('restored visitor target missing from current authorized choices is never silently replaced', async () => {
  const Api = {epoch: 1, request: async () => ({items: [{id: 'other', roomId: '1'}]})};
  const { ReadScope } = await load('features/ReadScope.ets', {Api});
  const { SubmissionKeys } = await load('services/SubmissionKeys.ets');
  const { VisitorController } = await load('features/visitors/VisitorController.ets', {Api, ReadScope, SubmissionKeys, util: {generateRandomUUID: () => 'key'}});
  const visitors = new VisitorController({setError() {}, fail: e => { throw e; }});
  visitors.state.draftRestored = true;
  visitors.state.visitedStudentId = 'original';
  await visitors.loadStudents();
  assert.equal(visitors.state.visitedStudentId, '');
});

test("latest repair filter wins even when an earlier request completes last in the same login", async () => {
  const responses = [];
  const Api = {
    epoch: 1,
    assertCurrent() {},
    request: (path) =>
      new Promise((resolve) => responses.push({ path, resolve })),
  };
  const repairs = await controller(Api);
  repairs.state.repairFilter = "SUBMITTED";
  const old = repairs.loadRepairs();
  repairs.state.repairFilter = "PROCESSING";
  const current = repairs.loadRepairs();
  responses[1].resolve({ items: [{ id: "processing" }], total: 1 });
  await current;
  responses[0].resolve({ items: [{ id: "submitted" }], total: 8 });
  await old;
  assert.deepEqual(repairs.state.repairs, [{ id: "processing" }]);
  assert.equal(repairs.state.repairTotal, 1);
  assert.equal(repairs.state.listLoading, false);
});

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

test("an old repair request cannot clear the current query spinner or surface its error", async () => {
  const oldResponse = deferred(),
    currentResponse = deferred();
  let n = 0;
  const Api = {
    epoch: 1,
    assertCurrent() {},
    request: () => (++n === 1 ? oldResponse.promise : currentResponse.promise),
  };
  const repairs = await controller(Api);
  const old = repairs.loadRepairs();
  const current = repairs.loadRepairs();
  oldResponse.reject(new Error("obsolete request"));
  await old;
  assert.equal(repairs.state.listLoading, true);
  currentResponse.resolve({ items: [], total: 0 });
  await current;
  assert.equal(repairs.state.listLoading, false);
});

test("homepage preview arriving after navigation cannot replace the full repair list", async () => {
  const preview = deferred();
  const Api = {
    epoch: 1,
    assertCurrent() {},
    request: async (path) => {
      if (path === "/statistics/overview") return { openRepairs: 8 };
      if (path.includes("pageSize=3")) return preview.promise;
      return { items: [{ id: "full-list-row" }], total: 35 };
    },
  };
  const repairs = await controller(Api);
  const { ReadScope } = await load("features/ReadScope.ets", { Api });
  const { pendingRepairPaths, newestUnique } = await load(
    "services/PendingRepairs.ets",
  );
  const { HomeController } = await load("features/home/HomeController.ets", {
    Api,
    ReadScope,
    pendingRepairPaths,
    newestUnique,
  });
  const home = new HomeController({
    fail: (error) => {
      throw error;
    },
  });
  home.manager = true;
  const homeRead = home.loadHome();
  await new Promise((resolve) => setImmediate(resolve));
  await repairs.loadRepairs();
  preview.resolve({
    items: [{ id: "preview-row", submittedAt: "2026-11-12T00:00:00Z" }],
    total: 8,
  });
  await homeRead;
  assert.deepEqual(repairs.state.repairs, [{ id: "full-list-row" }]);
  assert.equal(repairs.state.repairTotal, 35);
  assert.equal(repairs.state.listLoading, false);
  assert.deepEqual(
    home.state.repairs.map((row) => row.id),
    ["preview-row"],
  );
});

test("reset retires outstanding reads before another feature session uses the same controller", async () => {
  const response = deferred();
  const Api = { epoch: 1, assertCurrent() {}, request: () => response.promise };
  const repairs = await controller(Api);
  const loading = repairs.loadRepairs();
  repairs.reset();
  repairs.state.repairSearch = "new draft";
  response.resolve({ items: [{ id: "old session" }], total: 1 });
  await loading;
  assert.deepEqual(repairs.state.repairs, []);
  assert.equal(repairs.state.repairSearch, "new draft");
  assert.equal(repairs.state.listLoading, false);
});

test("reset also retires an in-flight repair submission without clearing a new draft or navigating", async () => {
  const response = deferred();
  let navigated = false;
  const Api = { epoch: 1, assertCurrent() {}, post: () => response.promise };
  const repairs = await controller(
    Api,
    {
      openRepair: async () => {
        navigated = true;
      },
    },
    { clear() {} },
  );
  Object.assign(repairs.state, {
    repairTitle: "水管漏水",
    repairDescription: "洗手池水管漏水",
    contactPhone: "13800001234",
    contactName: "演示学生",
  });
  const saving = repairs.submitRepair();
  repairs.reset();
  repairs.state.repairTitle = "new draft";
  response.resolve({ id: "old-repair" });
  await saving;
  assert.equal(repairs.state.repairTitle, "new draft");
  assert.equal(navigated, false);
});

test("a list completion does not dismiss a newer detail progress indicator", async () => {
  const list = deferred(),
    detail = deferred();
  const Api = {
    epoch: 1,
    assertCurrent() {},
    request: (path) =>
      path.startsWith("/repairs?page=")
        ? list.promise
        : path.endsWith("/events")
          ? Promise.resolve([])
          : detail.promise,
  };
  const repairs = await controller(Api);
  const listRead = repairs.loadRepairs();
  const detailRead = repairs.loadDetail({ id: "detail-order" });
  list.resolve({ items: [], total: 0 });
  await listRead;
  assert.equal(repairs.state.detailLoading, true);
  detail.resolve({ id: "detail-order", title: "门锁松动" });
  await detailRead;
  assert.equal(repairs.state.detailLoading, false);
  assert.equal(repairs.state.selected.id, "detail-order");
});

test("leaving a workspace retires a late read error before it can paint the next page", async () => {
  const response = deferred();
  const failures = [];
  const Api = { epoch: 1, request: () => response.promise };
  const repairs = await controller(Api, {
    fail: (error) => failures.push(error.message),
  });
  const loading = repairs.loadRepairs();
  repairs.suspendReads();
  response.reject(new Error("离开的工单列表错误"));
  await loading;
  assert.deepEqual(failures, []);
  assert.equal(repairs.state.listLoading, false);
});

test("detail A → close → A ignores the first response even though the order ID matches", async () => {
  const first = deferred(),
    second = deferred();
  let reads = 0;
  const Api = {
    epoch: 1,
    request: (path) =>
      path.endsWith("/events")
        ? Promise.resolve([{ id: "new-event" }])
        : ++reads === 1
          ? first.promise
          : second.promise,
  };
  const repairs = await controller(Api);
  const oldRead = repairs.loadDetail({ id: "A" });
  repairs.suspendReads();
  const currentRead = repairs.loadDetail({ id: "A" });
  second.resolve({ id: "A", title: "new detail" });
  await currentRead;
  first.resolve({ id: "A", title: "old detail" });
  await oldRead;
  assert.equal(repairs.state.selected.title, "new detail");
  assert.deepEqual(repairs.state.events, [{ id: "new-event" }]);
  assert.equal(repairs.state.detailLoading, false);
});

test("closing a page still forwards a current-session expiry to the session owner", async () => {
  const response = deferred(),
    failures = [];
  const Api = { epoch: 1, request: () => response.promise };
  const repairs = await controller(Api, {
    fail: (error) => failures.push(error.status),
  });
  const loading = repairs.loadRepairs();
  repairs.suspendReads();
  Api.epoch = 2;
  response.reject(
    Object.assign(new Error("session expired"), {
      status: 401,
      sessionEpoch: 2,
    }),
  );
  await loading;
  assert.deepEqual(failures, [401]);
});

test("current residence refresh survives leaving the homepage while its previews stop", async () => {
  const room = deferred();
  const requests = [];
  const Api = {
    epoch: 1,
    request: (path) => {
      requests.push(path);
      return room.promise;
    },
  };
  const { ReadScope } = await load("features/ReadScope.ets", { Api });
  const { HomeController } = await load("features/home/HomeController.ets", {
    Api,
    ReadScope,
  });
  const home = new HomeController({
    fail: (error) => {
      throw error;
    },
  });
  const loading = home.loadHome();
  home.suspendReads();
  room.resolve({ id: "current-room", roomNo: "A302" });
  await loading;
  assert.equal(home.state.room?.id, "current-room");
  assert.deepEqual(requests, ["/me/room"]);
  assert.equal(home.state.loading, false);
});

test("a RESULT upload begun for task A cannot enter task B after action draft changes", async () => {
  const uploaded = deferred();
  const Api = {
    epoch: 1,
    token: "manager",
    choose: async () => ["result-for-A"],
    upload: () => uploaded.promise,
  };
  const { PhotoDraftController } = await load(
    "features/photos/PhotoDraftController.ets",
    { Api },
  );
  const photos = new PhotoDraftController((error) => {
    throw error;
  });
  const repairs = await controller(Api, {}, photos);
  const unsubscribe = photos.subscribe((snapshot) =>
    repairs.photoChanged(snapshot),
  );
  repairs.state.selected = { id: "A" };
  await repairs.beginAction("finish");
  const pending = photos.choose("RESULT", {});
  await new Promise((resolve) => setImmediate(resolve));
  repairs.state.selected = { id: "B" };
  await repairs.beginAction("finish");
  uploaded.resolve({ uri: "result-for-A", asset: { id: "asset-for-A" } });
  await pending;
  assert.equal(repairs.state.selected.id, "B");
  assert.deepEqual(repairs.state.resultPhotos, []);
  assert.equal(repairs.state.uploading, false);
  unsubscribe();
});

test("starting another inspection recheck retires the prior room photo draft", async () => {
  const Api = {
    epoch: 1,
    choose: async () => ["photo-for-room-A"],
    upload: async (uri) => ({ uri, asset: { id: "A-photo" } }),
  };
  const { ReadScope } = await load("features/ReadScope.ets", { Api });
  const { PhotoDraftController } = await load(
    "features/photos/PhotoDraftController.ets",
    { Api },
  );
  const { HygieneController } = await load(
    "features/hygiene/HygieneController.ets",
    {
      Api,
      ReadScope,
      util: { generateRandomUUID: () => "key" },
      SubmissionKeys: class {
        clear() {}
      },
      today: () => "2026-11-12",
      beijingClock: () => "10:30",
    },
  );
  const photos = new PhotoDraftController((error) => {
    throw error;
  });
  const hygiene = new HygieneController({ navigate() {} }, photos);
  const unsubscribe = photos.subscribe((snapshot) =>
    hygiene.photoChanged(snapshot),
  );
  await photos.choose("INSPECTION", {});
  assert.equal(hygiene.state.inspectionPhotos.length, 1);
  hygiene.recheck({ id: "inspection-B", roomId: "B", round: "ROUTINE" });
  assert.equal(hygiene.state.inspectionRoomId, "B");
  assert.deepEqual(hygiene.state.inspectionPhotos, []);
  unsubscribe();
});

test("the login room is available immediately and an old room refresh cannot enter a new account", async () => {
  const room = deferred();
  const Api = { epoch: 1, request: () => room.promise };
  const { ReadScope } = await load("features/ReadScope.ets", { Api });
  const { HomeController } = await load("features/home/HomeController.ets", {
    Api,
    ReadScope,
  });
  const home = new HomeController({
    fail: (error) => {
      throw error;
    },
  });
  home.initialize({ role: "STUDENT", currentRoom: { id: "A" } });
  assert.equal(home.state.room.id, "A");
  const old = home.loadHome();
  home.reset();
  Api.epoch++;
  home.initialize({ role: "STUDENT", currentRoom: { id: "B" } });
  room.resolve({ id: "old-A" });
  await old;
  assert.equal(home.state.room.id, "B");
});
