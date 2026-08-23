package com.cbc_more_content.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The banner put on anything still being built, so nobody takes an unfinished mechanic
 * for a broken one. Kept in one place so the wording and colour cannot drift apart
 * between the items that carry it.
 */
public final class WorkInProgress {
    private WorkInProgress() {
    }

    public static void append(List<Component> tooltip) {
        tooltip.add(Component.translatable("tooltip.cbc_more_content.wip")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }
}
