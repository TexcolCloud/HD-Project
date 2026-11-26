import fs from "node:fs";
import net from "node:net";

const arg = process.argv[2] || "{}";
const config = JSON.parse(
  arg.trim().startsWith("{") ? arg : fs.readFileSync(arg, "utf8"),
);
const pidFile = config.pidFile;
const mappings = config.mappings || [];

if (pidFile) {
  fs.writeFileSync(pidFile, String(process.pid), "utf8");
}

const servers = [];

for (const { listenHost, listenPort, targetHost, targetPort } of mappings) {
  const server = net.createServer((client) => {
    client.setNoDelay(true);
    const upstream = net.connect(targetPort, targetHost, () => {
      upstream.setNoDelay(true);
    });
    client.pipe(upstream);
    upstream.pipe(client);
    client.on("error", () => upstream.destroy());
    upstream.on("error", () => client.destroy());
    client.on("close", () => upstream.destroy());
    upstream.on("close", () => client.destroy());
  });
  server.on("error", (err) => {
    console.error(
      `[lan-relay] Failed to bind ${listenHost}:${listenPort} -> ${err.message}`,
    );
    process.exit(1);
  });
  server.listen(listenPort, listenHost, () => {
    console.log(
      `[lan-relay] Listening on ${listenHost}:${listenPort} -> ${targetHost}:${targetPort}`,
    );
  });
  servers.push(server);
}

function shutdown() {
  for (const s of servers) {
    try {
      s.close();
    } catch {}
  }
  if (pidFile && fs.existsSync(pidFile)) {
    try {
      fs.unlinkSync(pidFile);
    } catch {}
  }
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
