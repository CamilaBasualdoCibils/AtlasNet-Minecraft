const fs = require('node:fs/promises');
const path = require('node:path');
async function main() {
  const root = process.cwd();
  const directory = path.resolve(process.argv[2]);
  const {prepare} = require(path.resolve('AtlasNet/tools/vscode-local-stack/stack.js'));
  const config = JSON.parse((await fs.readFile('.vscode/atlasnet-stack.json', 'utf8'))
    .replaceAll('${workspaceFolder}', root));
  config.clusterPort = 44710;
  config.handshakePort = 44720;
  config.valkeyURI = 'tcp://127.0.0.1:17471';
  config.clusterSettings.controller.spatial.recompute_interval = '5s';
  config.clusterSettings.controller.heuristic = 'example.xsplit2d';
  config.clusterSettings.region_transfer.batch_interval = process.env.MIGRATION_BATCH || '50ms';
  config.groups.forEach((group,i) => {
    group.capabilities = group.capabilities.filter(c => c !== 'ControlPanel');
    delete group.nodeSettings.control_panel;
    group.nodeSettings.ingress[0].port = 5971 + i;
    group.nodeSettings.metrics = {port: 17473+i};
    group.nodeSettings.ingress[0].spawn =
      `Static(minecraft:overworld,${i===0?-12:12}:-60:${i===0?0:6})`;
  });
  const {launches,files} = await prepare(config, directory, {logDirectory:directory});
  for (const [file,contents] of files) {
    await fs.mkdir(path.dirname(file),{recursive:true});
    await fs.writeFile(file,contents);
  }
  await fs.writeFile(path.join(directory,'launch.json'),JSON.stringify(launches,null,2));
}
main().catch(error => { console.error(error); process.exitCode=1; });
