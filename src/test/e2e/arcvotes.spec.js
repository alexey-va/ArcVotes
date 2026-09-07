import assert from 'node:assert/strict';
import { test, expect } from '@drownek/plugwright';

const CALLBACK_SECRET = process.env.ARC_VOTES_MONITORING_MINECRAFT_SECRET;

async function postVote(playerOrName, timestamp) {
  assert.ok(CALLBACK_SECRET, 'callback secret must be supplied by the E2E environment');
  const nickname = typeof playerOrName === 'string' ? playerOrName : playerOrName.username;
  const response = await fetch('http://127.0.0.1:9187/callbacks/monitoring-minecraft', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${CALLBACK_SECRET}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ nickname, server_id: 43, timestamp }),
  });
  assert.equal(response.status, 200, await response.text());
}

async function balance(player, currency) {
  player.clearMessages();
  const since = player.getMessageBufferIndex();
  player.chat(`/balance ${player.username}${currency ? ` ${currency}` : ''}`);
  await expect(player).toHaveReceivedMessage(/\d+(?:[.,]\d+)?/, {
    since,
    timeout: 10000,
  });
  const text = player.messageBuffer.slice(since).join('\n');
  const match = text.match(/\bhas\s+(-?\d+(?:[.,]\d+)?)([kKmMbB])?/i);
  assert.ok(match, `Balance response did not contain a number: ${text}`);
  const multiplier = { k: 1e3, m: 1e6, b: 1e9 }[match[2]?.toLowerCase()] ?? 1;
  return Number(match[1].replace(',', '.')) * multiplier;
}

test('/vote chat renders the configured voting sites', async ({ player }) => {
  player.chat('/vote chat');
  await expect(player).toHaveReceivedMessage('MinecraftRating');
  await expect(player).toHaveReceivedMessage('HotMC');
});

test('/vote gui opens the real menu and /vote status reports provider readiness', async ({ player }) => {
  player.chat('/vote gui');
  const gui = await player.gui({ title: /Voting sites|Голосования/ });
  const minecraft = gui.locator((item) => {
    const text = [item.getDisplayName(), ...item.getLore()].join(' ');
    return /MinecraftRating|Minecraft/i.test(text);
  });
  await expect(minecraft).toHaveLore('Click');
  const minecraftText = `${minecraft.displayName()} ${minecraft.loreText()}`;
  assert.match(minecraftText, /MinecraftRating|Minecraft/i);
  assert.notEqual(minecraft.loreText().trim(), '', 'vote item must expose status/action lore');
  await minecraft.click();
  await expect(player).toHaveReceivedMessage(/Voting link|Ссылка для голосования/i);
  await player.makeOp();
  player.chat('/vote status');
  await expect(player).toHaveReceivedMessage(/MySQL:.*(working|работает)/i);
  await expect(player).toHaveReceivedMessage(/Rewards:.*(working|работает)/i);
});

test('signed callback delivers real vault and token rewards exactly once', async ({ player }) => {
  const timestamp = new Date().toISOString();
  await player.makeOp();
  assert.equal(await balance(player), 0);
  assert.equal(await balance(player, 'tokens'), 0);

  await postVote(player, timestamp);
  await expect(player).toHaveReceivedMessage(/MonitoringMinecraft vote was recorded/i, { timeout: 15000 });
  await expect(player).toHaveReceivedMessage(/\+1000/);
  await expect(player).toHaveReceivedMessage(/\+3/);
  assert.equal(await balance(player), 1000);
  assert.equal(await balance(player, 'tokens'), 3);

  await postVote(player, timestamp);
  await new Promise((resolve) => setTimeout(resolve, 3000));
  assert.equal(await balance(player), 1000, 'duplicate callback minted additional vault');
  assert.equal(await balance(player, 'tokens'), 3, 'duplicate callback minted additional tokens');
});

test('offline signed callback is delivered on first login and survives reconnect', async ({ createPlayer }) => {
  const username = 'OfflineVoteE2E';
  const timestamp = new Date().toISOString();
  await postVote(username, timestamp);

  const player = await createPlayer({ username });
  await player.makeOp();
  await expect(player).toHaveReceivedMessage(/MonitoringMinecraft vote was recorded/i, { timeout: 15000 });
  await expect(player).toHaveReceivedMessage(/\+1000/);
  await expect(player).toHaveReceivedMessage(/\+3/);
  assert.equal(await balance(player), 1000);
  assert.equal(await balance(player, 'tokens'), 3);

  await player.rejoin();
  assert.equal(await balance(player), 1000, 'reconnect replayed offline vault reward');
  assert.equal(await balance(player, 'tokens'), 3, 'reconnect replayed offline token reward');

  await postVote(player, timestamp);
  await new Promise((resolve) => setTimeout(resolve, 3000));
  assert.equal(await balance(player), 1000, 'duplicate offline callback minted additional vault');
  assert.equal(await balance(player, 'tokens'), 3, 'duplicate offline callback minted additional tokens');
});
