package com.example.arslauria;

import com.hollingsworth.arsnouveau.api.mana.IManaCap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
    // 1) Явно указываем, что токен — для IManaCap
    public static final Capability<IManaCap> MANA_CAP =
            CapabilityManager.get(new CapabilityToken<IManaCap>() {});

    private static final Logger LOGGER = LogManager.getLogger();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mana")
                        .requires(src -> src.getEntity() instanceof ServerPlayer)
                        .executes(ctx -> {
                            try {
                                return printMana(ctx);
                            } catch (CommandSyntaxException e) {
                                // Некорректный источник (не игрок)
                                ctx.getSource()
                                        .sendFailure(Component.literal("Эту команду может запускать только игрок"));
                                return 0;
                            } catch (Throwable t) {
                                // Логируем полный стек и отдаем игроку минимальный текст
                                LOGGER.error("Ошибка при выполнении /mana", t);
                                ctx.getSource()
                                        .sendFailure(Component.literal(
                                                "Внутренняя ошибка /mana: " + t.getClass().getSimpleName()
                                        ));
                                return 0;
                            }
                        })
        );
    }

    private static int printMana(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        // 2) Убедились, что Optional<IManaCap>
        Optional<IManaCap> capOpt = player.getCapability(MANA_CAP).resolve();
        if (capOpt.isEmpty()) {
            ctx.getSource()
                    .sendFailure(Component.literal("Нет возможности Ars Nouveau Mana!"));
            return 0;
        }

        IManaCap manaCap = capOpt.get();
        double current, max;

        try {
            // пробуем несколько имён методов
            current = invokeNumber(manaCap, "getCurrentMana", "getMana");
            max     = invokeNumber(manaCap, "getMaxMana");
        } catch (ReflectiveOperationException e) {
            // если не нашли ни одного метода
            ctx.getSource()
                    .sendFailure(Component.literal(
                            "Методы маны не найдены (проверьте версию API Ars Nouveau)"
                    ));
            return 0;
        }

        String text = String.format("Your Ars Nouveau Mana: %.0f/%.0f", current, max);

        // 3) sendSuccess требует Supplier<Component>
        ctx.getSource()
                .sendSuccess(() -> Component.literal(text), false);

        return 1;
    }

    /**
     * Ищет в объекте метод из списка names, вызывает его и конвертирует результат в double
     */
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
                    throw new ClassCastException(
                            name + " вернул не Number, а " + result.getClass().getSimpleName()
                    );
                }
            } catch (NoSuchMethodException ignored) {
                // метод с таким именем не найден — пробуем следующее
            }
        }
        throw new NoSuchMethodException(
                "Не найден ни один метод: " + String.join("/", names)
        );
    }
}
