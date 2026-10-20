package wiki.ica.xiaomiao.mcluaide.client;

/** A Minecraft Screen only captures game input; every control is rendered by ImGui. */
interface NativeUiScreen {
    void drawUi();
    void rendererFailed(Throwable error);
    default void frameRendered() { }
}
