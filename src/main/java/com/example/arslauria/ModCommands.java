package com.example.arslauria;

import com.hollingsworth.arsnouveau.api.mana.IManaCap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

public class ModCommands {
    public static final Capability<IManaCap> MANA_CAP =
            CapabilityManager.get(new CapabilityToken<IManaCap>() {});

    private static final Logger LOGGER = LogManager.getLogger();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mana")
                        .requires(src -> src.getEntity() instanceof ServerPlayer)
                        // /mana (показывает текущую ману)
                        .executes(ctx -> {
                            try {
                                return printMana(ctx);
                            } catch (CommandSyntaxException e) {
                                ctx.getSource()
                                        .sendFailure(Component.literal("Эту команду может запускать только игрок"));
                                return 0;
                            } catch (Throwable t) {
                                LOGGER.error("Ошибка при выполнении /mana", t);
                                ctx.getSource()
                                        .sendFailure(Component.literal("Внутренняя ошибка /mana: " + t.getClass().getSimpleName()));
                                return 0;
                            }
                        })
                        // добавляем подкоманду /mana set <amount>
                        .then(Commands.literal("set")
                                // менять можно только с правами оператора (permission level 2)
                                .requires(src -> src.getEntity() instanceof ServerPlayer && src.hasPermission(2))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
                                        .executes(ctx -> {
                                            try {
                                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                return setMaxMana(ctx, amount);
                                            } catch (CommandSyntaxException e) {
                                                ctx.getSource()
                                                        .sendFailure(Component.literal("Эту команду может запускать только игрок"));
                                                return 0;
                                            } catch (Throwable t) {
                                                LOGGER.error("Ошибка при выполнении /mana set", t);
                                                ctx.getSource()
                                                        .sendFailure(Component.literal("Внутренняя ошибка /mana set: " + t.getClass().getSimpleName()));
                                                return 0;
                                            }
                                        })
                                )
                        )
        );
    }

    private static int printMana(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        Optional<IManaCap> capOpt = player.getCapability(MANA_CAP).resolve();
        if (capOpt.isEmpty()) {
            ctx.getSource()
                    .sendFailure(Component.literal("Нет возможности Ars Nouveau Mana!"));
            return 0;
        }

        IManaCap manaCap = capOpt.get();
        double current, max;

        try {
            current = invokeNumber(manaCap, "getCurrentMana", "getMana");
            max     = invokeNumber(manaCap, "getMaxMana", "getManaMax");
        } catch (ReflectiveOperationException e) {
            ctx.getSource()
                    .sendFailure(Component.literal("Методы маны не найдены (проверьте версию API Ars Nouveau)"));
            return 0;
        }

        String text = String.format("Your Ars Nouveau Mana: %.0f/%.0f", current, max);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * Установить максимальную ману игроку (через рефлексию).
     */
    private static int setMaxMana(CommandContext<CommandSourceStack> ctx, int amount)
            throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        Optional<IManaCap> capOpt = player.getCapability(MANA_CAP).resolve();
        if (capOpt.isEmpty()) {
            ctx.getSource()
                    .sendFailure(Component.literal("Нет возможности Ars Nouveau Mana!"));
            return 0;
        }

        IManaCap manaCap = capOpt.get();
        try {
            // Попробуем найти сеттер для max: common names
            invokeSetter(manaCap, amount,
                    "setMaxMana", "setManaMax", "setMax", "setMaxManaValue");

            // Если текущая манa > новый макс, попробуем установить текущую на новый макс
            double current = -1;
            try {
                current = invokeNumber(manaCap, "getCurrentMana", "getMana");
            } catch (ReflectiveOperationException ignored) {}

            if (current > amount) {
                try {
                    invokeSetter(manaCap, amount,
                            "setCurrentMana", "setMana", "setManaCurrent");
                } catch (ReflectiveOperationException ignored) {
                    // если не удалось установить текущую — молча пропускаем
                }
            }

            // Попытка синхронизировать capability с клиентом, если реализация предоставляет метод sync
            tryInvokeNoArgVoid(manaCap, "sync", "syncToClient", "sendUpdate");

            ctx.getSource().sendSuccess(() -> Component.literal("Max mana set to " + amount), true);
            return 1;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Не удалось установить max mana", e);
            ctx.getSource()
                    .sendFailure(Component.literal("Не удалось установить max mana: " + e.getClass().getSimpleName()));
            return 0;
        }
    }

    private static double invokeNumber(IManaCap cap, String... names)
            throws ReflectiveOperationException
    {
        Class<?> impl = cap.getClass();
        for (String name : names) {
            try {
                Method m = impl.getMethod(name);
                Object result = m.invoke(cap);
                if (result instanceof Number n) {
                    return n.doubleValue();
                } else {
                    throw new ClassCastException(name + " вернул не Number, а " + result.getClass().getSimpleName());
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("Не найден ни один метод: " + String.join("/", names));
    }

    /**
     * Находит метод-сеттер с одним числовым параметром и вызывает его.
     * Перебирает варианты примитивных типов: int, long, float, double.
     */
    private static void invokeSetter(IManaCap cap, double value, String... names) throws ReflectiveOperationException {
        Class<?> impl = cap.getClass();
        Class<?>[] paramTypes = new Class<?>[] { int.class, long.class, float.class, double.class };

        for (String name : names) {
            for (Class<?> pt : paramTypes) {
                try {
                    Method m = impl.getMethod(name, pt);
                    Object arg;
                    if (pt == int.class) arg = Integer.valueOf((int) value);
                    else if (pt == long.class) arg = Long.valueOf((long) value);
                    else if (pt == float.class) arg = Float.valueOf((float) value);
                    else arg = Double.valueOf(value);
                    m.invoke(cap, arg);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // пробуем следующую сигнатуру
                }
            }
        }
        throw new NoSuchMethodException("Не найден сеттер среди: " + String.join("/", names));
    }

    /**
     * Попытка вызвать метод без аргументов и без возвращаемого значения (sync-like).
     */
    private static void tryInvokeNoArgVoid(IManaCap cap, String... names) {
        Class<?> impl = cap.getClass();
        for (String name : names) {
            try {
                Method m = impl.getMethod(name);
                if (m.getReturnType() == void.class) {
                    m.invoke(cap);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }
}
