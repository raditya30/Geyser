/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.inventory.click;

import com.github.steveice10.mc.protocol.data.game.entity.metadata.ItemStack;
import com.github.steveice10.mc.protocol.data.game.inventory.ContainerActionType;
import com.github.steveice10.mc.protocol.data.game.inventory.ContainerType;
import com.github.steveice10.mc.protocol.data.game.inventory.MoveToHotbarAction;
import com.github.steveice10.mc.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.Inventory;
import org.geysermc.geyser.inventory.SlotType;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.inventory.CraftingInventoryTranslator;
import org.geysermc.geyser.translator.inventory.InventoryTranslator;
import org.geysermc.geyser.translator.inventory.PlayerInventoryTranslator;
import org.geysermc.geyser.util.InventoryUtils;
import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

public class ClickPlan {
    private final List<ClickAction> plan = new ArrayList<>();
    private final Int2ObjectMap<GeyserItemStack> simulatedItems;
    private GeyserItemStack simulatedCursor;
    private boolean simulating;

    private final GeyserSession session;
    private final InventoryTranslator translator;
    private final Inventory inventory;
    private final int gridSize;

    public ClickPlan(GeyserSession session, InventoryTranslator translator, Inventory inventory) {
        this.session = session;
        this.translator = translator;
        this.inventory = inventory;

        this.simulatedItems = new Int2ObjectOpenHashMap<>(inventory.getSize());
        this.simulatedCursor = session.getPlayerInventory().getCursor().copy();
        this.simulating = true;

        if (translator instanceof PlayerInventoryTranslator) {
            gridSize = 4;
        } else if (translator instanceof CraftingInventoryTranslator) {
            gridSize = 9;
        } else {
            gridSize = -1;
        }
    }

    private void resetSimulation() {
        this.simulatedItems.clear();
        this.simulatedCursor = session.getPlayerInventory().getCursor().copy();
    }

    public void add(Click click, int slot) {
        add(click, slot, false);
    }

    public void add(Click click, int slot, boolean force) {
        if (!simulating)
            throw new UnsupportedOperationException("ClickPlan already executed");

        if (click == Click.LEFT_OUTSIDE || click == Click.RIGHT_OUTSIDE) {
            slot = Click.OUTSIDE_SLOT;
        }

        ClickAction action = new ClickAction(click, slot, force);
        plan.add(action);
        simulateAction(action);
    }

    public void execute(boolean refresh) {
        //update geyser inventory after simulation to avoid net id desync
        resetSimulation();
        ListIterator<ClickAction> planIter = plan.listIterator();
        while (planIter.hasNext()) {
            ClickAction action = planIter.next();

            if (action.slot != Click.OUTSIDE_SLOT && translator.getSlotType(action.slot) != SlotType.NORMAL) {
                // Needed with Paper 1.16.5
                refresh = true;
            }

            //int stateId = stateIdHack(action);

            //simulateAction(action);

            ItemStack clickedItemStack;
            if (!planIter.hasNext() && refresh) {
                clickedItemStack = InventoryUtils.REFRESH_ITEM;
            } else if (action.click.actionType == ContainerActionType.DROP_ITEM || action.slot == Click.OUTSIDE_SLOT) {
                clickedItemStack = null;
            } else {
                //// The action must be simulated first as Java expects the new contents of the cursor (as of 1.18.1)
                //clickedItemStack = simulatedCursor.getItemStack(); TODO fix - this is the proper behavior but it terribly breaks 1.16.5
                clickedItemStack = getItem(action.slot).getItemStack();
            }

            ServerboundContainerClickPacket clickPacket = new ServerboundContainerClickPacket(
                    inventory.getId(),
                    inventory.getStateId(),
                    action.slot,
                    action.click.actionType,
                    action.click.action,
                    clickedItemStack,
                    Collections.emptyMap() // Anything else we change, at this time, should have a packet sent to address
            );

            simulateAction(action);

            session.sendDownstreamPacket(clickPacket);
        }

        session.getPlayerInventory().setCursor(simulatedCursor, session);
        for (Int2ObjectMap.Entry<GeyserItemStack> simulatedSlot : simulatedItems.int2ObjectEntrySet()) {
            inventory.setItem(simulatedSlot.getIntKey(), simulatedSlot.getValue(), session);
        }
        simulating = false;
    }

    public GeyserItemStack getItem(int slot) {
        return getItem(slot, true);
    }

    public GeyserItemStack getItem(int slot, boolean generate) {
        if (generate) {
            return simulatedItems.computeIfAbsent(slot, k -> inventory.getItem(slot).copy());
        } else {
            return simulatedItems.getOrDefault(slot, inventory.getItem(slot));
        }
    }

    public GeyserItemStack getCursor() {
        return simulatedCursor;
    }

    private void setItem(int slot, GeyserItemStack item) {
        if (simulating) {
            simulatedItems.put(slot, item);
        } else {
            inventory.setItem(slot, item, session);
        }
    }

    private void setCursor(GeyserItemStack item) {
        if (simulating) {
            simulatedCursor = item;
        } else {
            session.getPlayerInventory().setCursor(item, session);
        }
    }

    private void simulateAction(ClickAction action) {
        GeyserItemStack cursor = simulating ? getCursor() : session.getPlayerInventory().getCursor();
        switch (action.click) {
            case LEFT_OUTSIDE -> {
                setCursor(GeyserItemStack.EMPTY);
                return;
            }
            case RIGHT_OUTSIDE -> {
                if (!cursor.isEmpty()) {
                    cursor.sub(1);
                }
                return;
            }
        }

        GeyserItemStack clicked = simulating ? getItem(action.slot) : inventory.getItem(action.slot);
        if (translator.getSlotType(action.slot) == SlotType.OUTPUT) {
            switch (action.click) {
                case LEFT, RIGHT -> {
                    if (cursor.isEmpty() && !clicked.isEmpty()) {
                        setCursor(clicked.copy());
                    } else if (InventoryUtils.canStack(cursor, clicked)) {
                        cursor.add(clicked.getAmount());
                    }
                    reduceCraftingGrid(false);
                }
                case LEFT_SHIFT -> reduceCraftingGrid(true);
            }
        } else {
            switch (action.click) {
                case LEFT:
                    if (!InventoryUtils.canStack(cursor, clicked)) {
                        setCursor(clicked);
                        setItem(action.slot, cursor);
                    } else {
                        setCursor(GeyserItemStack.EMPTY);
                        clicked.add(cursor.getAmount());
                    }
                    break;
                case RIGHT:
                    if (cursor.isEmpty() && !clicked.isEmpty()) {
                        int half = clicked.getAmount() / 2; //smaller half
                        setCursor(clicked.copy(clicked.getAmount() - half)); //larger half
                        clicked.setAmount(half);
                    } else if (!cursor.isEmpty() && clicked.isEmpty()) {
                        cursor.sub(1);
                        setItem(action.slot, cursor.copy(1));
                    } else if (InventoryUtils.canStack(cursor, clicked)) {
                        cursor.sub(1);
                        clicked.add(1);
                    }
                    break;
                case SWAP_TO_HOTBAR_1:
                    swap(action.slot, inventory.getOffsetForHotbar(0), clicked);
                    break;
                case SWAP_TO_HOTBAR_2:
                    swap(action.slot, inventory.getOffsetForHotbar(1), clicked);
                    break;
                case SWAP_TO_HOTBAR_3:
                    swap(action.slot, inventory.getOffsetForHotbar(2), clicked);
                    break;
                case SWAP_TO_HOTBAR_4:
                    swap(action.slot, inventory.getOffsetForHotbar(3), clicked);
                    break;
                case SWAP_TO_HOTBAR_5:
                    swap(action.slot, inventory.getOffsetForHotbar(4), clicked);
                    break;
                case SWAP_TO_HOTBAR_6:
                    swap(action.slot, inventory.getOffsetForHotbar(5), clicked);
                    break;
                case SWAP_TO_HOTBAR_7:
                    swap(action.slot, inventory.getOffsetForHotbar(6), clicked);
                    break;
                case SWAP_TO_HOTBAR_8:
                    swap(action.slot, inventory.getOffsetForHotbar(7), clicked);
                    break;
                case SWAP_TO_HOTBAR_9:
                    swap(action.slot, inventory.getOffsetForHotbar(8), clicked);
                    break;
                case LEFT_SHIFT:
                    //TODO
                    break;
                case DROP_ONE:
                    if (!clicked.isEmpty()) {
                        clicked.sub(1);
                    }
                    break;
                case DROP_ALL:
                    setItem(action.slot, GeyserItemStack.EMPTY);
                    break;
            }
        }
    }

    /**
     * Swap between two inventory slots without a cursor. This should only be used with {@link ContainerActionType#MOVE_TO_HOTBAR_SLOT}
     */
    private void swap(int sourceSlot, int destSlot, GeyserItemStack sourceItem) {
        GeyserItemStack destinationItem = simulating ? getItem(destSlot) : inventory.getItem(destSlot);
        setItem(sourceSlot, destinationItem);
        setItem(destSlot, sourceItem);
    }

    private int stateIdHack(ClickAction action) {
        int stateId;
        if (inventory.getNextStateId() != -1) {
            stateId = inventory.getNextStateId();
        } else {
            stateId = inventory.getStateId();
        }

        // This is a hack.
        // Java will never ever send more than one container click packet per set of actions.
        // Bedrock might, and this would generally fall into one of two categories:
        // - Bedrock is sending an item directly from one slot to another, without picking it up, that cannot
        //   be expressed with a shift click
        // - Bedrock wants to pick up or place an arbitrary amount of items that cannot be expressed from
        //   one left/right click action.
        // When Bedrock does one of these actions and sends multiple packets, a 1.17.1+ server will
        // increment the state ID on each confirmation packet it sends back (I.E. set slot). Then when it
        // reads our next packet, because we kept the same state ID but the server incremented it, it'll be
        // desynced and send the entire inventory contents back at us.
        // This hack therefore increments the state ID to what the server will presumably send back to us.
        // (This won't be perfect, but should get us through most vanilla situations, and if this is wrong the
        // server will just send a set content packet back at us)
        if (inventory.getContainerType() == ContainerType.CRAFTING && CraftingInventoryTranslator.isCraftingGrid(action.slot)) {
            // 1.18.1 sends a second set slot update for any action in the crafting grid
            // And an additional packet if something is removed (Mojmap: CraftingContainer#removeItem)
            //TODO this code kind of really sucks; it's potentially possible to see what Bedrock sends us and send a PlaceRecipePacket
            int stateIdIncrements;
            GeyserItemStack clicked = getItem(action.slot);
            if (action.click == Click.LEFT) {
                if (!clicked.isEmpty() && !InventoryUtils.canStack(simulatedCursor, clicked)) {
                    // An item is removed from the crafting table; yes deletion
                    stateIdIncrements = 3;
                } else {
                    // We can stack and we add all the items to the crafting slot; no deletion
                    stateIdIncrements = 2;
                }
            } else if (action.click == Click.RIGHT) {
                if (simulatedCursor.isEmpty() && !clicked.isEmpty()) {
                    // Items are taken; yes deletion
                    stateIdIncrements = 3;
                } else if ((!simulatedCursor.isEmpty() && clicked.isEmpty()) || InventoryUtils.canStack(simulatedCursor, clicked)) {
                    // Adding our cursor item to the slot; no deletion
                    stateIdIncrements = 2;
                } else {
                    // ?? nothing I guess
                    stateIdIncrements = 2;
                }
            } else {
                if (session.getGeyser().getConfig().isDebugMode()) {
                    session.getGeyser().getLogger().debug("Not sure how to handle state ID hack in crafting table: " + plan);
                }
                stateIdIncrements = 2;
            }
            inventory.incrementStateId(stateIdIncrements);
        } else if (action.click.action instanceof MoveToHotbarAction) {
            // Two slot changes sent
            inventory.incrementStateId(2);
        } else {
            inventory.incrementStateId(1);
        }

        return stateId;
    }

    //TODO
    private void reduceCraftingGrid(boolean makeAll) {
        if (gridSize == -1)
            return;

        int crafted;
        if (!makeAll) {
            crafted = 1;
        } else {
            crafted = 0;
            for (int i = 0; i < gridSize; i++) {
                GeyserItemStack item = getItem(i + 1);
                if (!item.isEmpty()) {
                    if (crafted == 0) {
                        crafted = item.getAmount();
                    }
                    crafted = Math.min(crafted, item.getAmount());
                }
            }
        }

        for (int i = 0; i < gridSize; i++) {
            GeyserItemStack item = getItem(i + 1);
            if (!item.isEmpty())
                item.sub(crafted);
        }
    }

    /**
     * @return a new set of all affected slots.
     */
    @Contract("-> new")
    public IntSet getAffectedSlots() {
        IntSet affectedSlots = new IntOpenHashSet();
        for (ClickAction action : plan) {
            if (translator.getSlotType(action.slot) == SlotType.NORMAL && action.slot != Click.OUTSIDE_SLOT) {
                affectedSlots.add(action.slot);
            }
        }
        return affectedSlots;
    }

    private record ClickAction(Click click, int slot, boolean force) {
    }
}
