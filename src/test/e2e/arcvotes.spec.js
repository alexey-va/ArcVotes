import assert from 'node:assert/strict';
import { test, expect } from '@drownek/plugwright';

const CALLBACK_SECRET = process.env.ARC_VOTES_MONITORING_MINECRAFT_SECRET;

async function postVote(player, timestamp) {
  assert.ok(CALLBACK_SECRET, 'callback secret must be supplied by the E2E environment');
  const response = await fetch('http://127.0.0.1:9187/callbacks/monitoring-minecraft', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${CALLBACK_SECRET}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ nickname: player.username, server_id: 43, timestamp }),
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
  const values = [...text.matchAll(/(?<![A-Za-z])\d+(?:[.,]\d+)?/g)].map((match) => Number(match[0].replace(',', '.')));
  assert.ok(values.length > 0, `Balance response did not contain a number: ${text}`);
  return values.at(-1);
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
  const minecraftText = [minecraft.getDisplayName(), ...minecraft.getLore()].join(' ');
  assert.match(minecraftText, /MinecraftRating|Minecraft/i);
  assert.notEqual(minecraft.getLore().join(' ').trim(), '', 'vote item must expose status/action lore');
  await minecraft.click();
  await expect(player).toHaveReceivedMessage(/Voting link|Ссылка для голосования/i);
  await player.makeOp();
  player.chat('/vote status');
  await expect(player).toHaveReceivedMessage(/MySQL:.*(working|работает)/i);
  await expect(player).toHaveReceivedMessage(/Rewards:.*(working|работает)/i);
});

test('signed callback delivers real vault and token rewards exactly once', async ({ player }) => {
  const timestamp = new Date().toISOString();
  assert.equal(await balance(player), 0);
  assert.equal(await balance(player, 'tokens'), 0);

  player.bot.quit();
  await new Promise((resolve) => setTimeout(resolve, 500));
  await postVote(player, timestamp);
  await player.rejoin({ clearMessages: true });
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
