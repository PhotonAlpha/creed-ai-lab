package com.creed.parent.utils;

import com.itextpdf.html2pdf.attach.ProcessorContext;
import com.itextpdf.html2pdf.attach.impl.tags.SpanTagWorker;
import com.itextpdf.layout.IPropertyContainer;
import com.itextpdf.layout.element.Text;
import com.itextpdf.styledxmlparser.node.IElementNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/15/25
 */
@Slf4j
public class AnnotatedSpanTagWorker extends SpanTagWorker {
    private String comment;
    public AnnotatedSpanTagWorker(IElementNode element, ProcessorContext context) {
        super(element, context);
        this.comment = element.getAttribute("data-comment");
    }

    @Override
    public void processEnd(IElementNode element, ProcessorContext context) {
        super.processEnd(element, context);
        log.info("AnnotatedSpanTagWorker comment:{}", comment);
        List<IPropertyContainer> container = getOwnLeafElements();
        for (IPropertyContainer propertyContainer : container) {
            if (propertyContainer instanceof Text el) {
                el.setNextRenderer(new AnnotatedTextRender(el, comment));
            }
        }

    }
}
