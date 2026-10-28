package cn.bigfish.conversation;

import cn.bigfish.contract.LiveEvent;

public final class GiftTemplates {
    private GiftTemplates() {}
    public static String render(LiveEvent event) {
        String name = event.username().isBlank() ? "朋友" : event.username();
        return switch (event.type()) {
            case GIFT -> "谢谢" + name + "送来的" + event.payload().get("quantity") + "个"
                + event.payload().get("giftName") + "！";
            case FOLLOW -> "谢谢" + name + "的关注！";
            case ENTRANCE -> "欢迎" + name + "来到直播间！";
            default -> throw new IllegalArgumentException("Not a template event");
        };
    }
}
