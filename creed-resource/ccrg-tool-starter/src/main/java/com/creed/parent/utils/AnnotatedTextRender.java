package com.creed.parent.utils;

import com.itextpdf.commons.utils.MessageFormatUtil;
import com.itextpdf.io.font.otf.GlyphLine;
import com.itextpdf.io.logs.IoLogMessageConstant;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfString;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfTextAnnotation;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.layout.LayoutArea;
import com.itextpdf.layout.renderer.DrawContext;
import com.itextpdf.layout.renderer.IRenderer;
import com.itextpdf.layout.renderer.TextRenderer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/15/25
 */
@Slf4j
public class AnnotatedTextRender extends TextRenderer {
    private final String comment;
    private Text textElement;

    public AnnotatedTextRender(Text textElement, String comment) {
        super(textElement);
        this.textElement = textElement;
        this.comment = comment;
    }

    public AnnotatedTextRender(Text textElement, String text, String comment) {
        super(textElement, text);
        this.textElement = textElement;
        this.comment = comment;
    }

    public AnnotatedTextRender(TextRenderer other, String comment) {
        super(other);
        this.comment = comment;
    }
    /**
     * description AnnotatedTextRender
     * https://kb.itextpdf.com/itext/how-to-add-an-in-reply-to-annotation\
     * https://kb.itextpdf.com/itext/itext-7-jump-start-tutorial-chapter-4#c04e01_04_textmarkupannotation
     * @author EthanCao
     * @since 7/15/25
     */
    @Override
    public void draw(DrawContext drawContext) {
        super.draw(drawContext);
        Rectangle rectangle = getOccupiedAreaBBox();
        LayoutArea layoutArea = getOccupiedArea();
        log.info("comment:{} {} {} {} {}", comment, rectangle.getX(), rectangle.getY(), rectangle.getWidth(), rectangle.getHeight());
        PdfPage page = drawContext.getDocument().getPage(layoutArea.getPageNumber());
        PdfAnnotation annotation = new PdfTextAnnotation(new Rectangle(rectangle.getX() + 10, rectangle.getY() + 10, 20, 20))
                .setOpen(true)
                .setIconName(new PdfName("Help"))
                .setColor(ColorConstants.GREEN)
                .setContents(comment)
                .setBorder(new PdfArray(new float[]{100, 600, 200, 100}))
                .setTitle(new PdfString("author"));
        /* PdfAnnotation annotation = PdfTextMarkupAnnotation.createHighLight(
                new Rectangle(105, 790, 64, 10),
                new float[] {rectangle.getX(), rectangle.getY(), 105, 790, 169, 800, 105, 800 })
                .setColor(ColorConstants.YELLOW)
                .setTitle(new PdfString("Hello!"))
                .setContents(new PdfString("I'm a popup."))
                .setTitle(new PdfString("iText"))

                .setRectangle(new PdfArray(new float[] { rectangle.getX(), rectangle.getY(), 30, 30 })); */

        page.addAnnotation(annotation);
    }

    @Override
    protected TextRenderer createCopy(GlyphLine gl, PdfFont font) {

        if (AnnotatedTextRender.class != this.getClass()) {
            Logger logger = LoggerFactory.getLogger(AnnotatedTextRender.class);
            logger.error(MessageFormatUtil.format(IoLogMessageConstant.CREATE_COPY_SHOULD_BE_OVERRIDDEN));
        }
        AnnotatedTextRender copy = new AnnotatedTextRender(this, comment);
        copy.setProcessedGlyphLineAndFont(gl, font);
        return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IRenderer getNextRenderer() {
        if (AnnotatedTextRender.class != this.getClass()) {
            Logger logger = LoggerFactory.getLogger(AnnotatedTextRender.class);
            logger.error(MessageFormatUtil.format(
                    IoLogMessageConstant.GET_NEXT_RENDERER_SHOULD_BE_OVERRIDDEN));
        }
        return new AnnotatedTextRender((Text)modelElement, comment);
    }
}
