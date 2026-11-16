package io.github.leohou0430.llmgw.conversion;

/**
 * 阶段1仅内联 base64；{@code fileRef} 预留给阶段2/3 的 Files 引用，当前恒为 null。
 * {@code detail} 对应 image_url.detail（low/high/auto/original），可空；取值合法性由编解码层校验，语义层原样承载。
 * 内联表示要求 {@code mediaType} 与 {@code base64Data} 同时非空，否则要求 {@code fileRef} 非空；不接受空图片部件。
 */
public record ImagePart(String mediaType, String base64Data, String fileRef, String detail) implements ContentPart {
    public ImagePart {
        boolean inline = mediaType != null && base64Data != null;
        if (!inline && fileRef == null) {
            throw new IllegalArgumentException("ImagePart requires inline mediaType+base64Data or non-null fileRef");
        }
    }
}