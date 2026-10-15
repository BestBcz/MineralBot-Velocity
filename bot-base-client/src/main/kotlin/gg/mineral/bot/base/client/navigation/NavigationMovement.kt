package gg.mineral.bot.base.client.navigation

import gg.mineral.bot.api.controls.Key
import gg.mineral.bot.api.controls.Keyboard

/** Each navigation command describes the complete movement state, including sneak. */
internal object NavigationMovement {
    private val types=setOf(Key.Type.KEY_W,Key.Type.KEY_A,Key.Type.KEY_S,Key.Type.KEY_D,
        Key.Type.KEY_SPACE,Key.Type.KEY_LCONTROL,Key.Type.KEY_LSHIFT)

    fun apply(keyboard: Keyboard,keys: Set<Key.Type>) {
        (types-keys).forEach { keyboard.unpressKey(it) }
        keys.forEach { keyboard.pressKey(it) }
    }
}
