import assert from 'node:assert/strict';
import { test, expect } from '@drownek/plugwright';

test('/vote chat renders the configured voting sites', async ({ player }) => {
  player.chat('/vote chat');
  await expect(player).toHaveReceivedMessage('MinecraftRating');
  await expect(player).toHaveReceivedMessage('HotMC');
});

test('/vote gui opens the real menu and /vote status reports link-only mode', async ({ player }) => {
  player.chat('/vote gui');
  const gui = await player.gui({ title: /Voting sites|Голосования/ });
  const minecraft = gui.locator((item) => item.name === 'gold_ingot');
  assert.match(minecraft.displayName(), /MinecraftRating|Minecraft/i);
  assert.notEqual(minecraft.loreText().trim(), '', 'vote item must expose status/action lore');
  await minecraft.click();
  await expect(player).toHaveReceivedMessage(/Voting link|Ссылка для голосования/i);
  await player.makeOp();
  player.chat('/vote status');
  await expect(player).toHaveReceivedMessage(/MySQL:.*(not working|не работает)/i);
});
