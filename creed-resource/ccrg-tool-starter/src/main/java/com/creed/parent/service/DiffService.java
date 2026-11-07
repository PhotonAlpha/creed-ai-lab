package com.creed.parent.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.creed.parent.controller.vo.PropertyReviewVo;
import com.creed.parent.dal.model.DiffDisplay;
import com.creed.parent.dal.model.DiffInfo;
import com.creed.parent.dal.model.DiffPosition;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 25/6/25
 */
@Slf4j
@Component
public class DiffService {
    private static final Pattern URL_PATTERN = Pattern.compile(".*(?<origin>URL\\[.*)");
    @Getter
    @Setter
    private BiFunction<List<String>, DiffInfo, String> extractOriginFunc = (originList, info) -> {
        if (CollectionUtils.isNotEmpty(originList)) {
            Integer index = info.getLineNumber();
            if (index <= originList.size()) {
                String value = originList.get(index - 1);
                value = StringUtils.defaultIfBlank(value, "");
                Matcher matcher = URL_PATTERN.matcher(value);
                if (matcher.matches()) {
                    return matcher.group("origin");
                }
            }
        }
        return StringUtils.EMPTY;
    };
    public List<DiffDisplay> diffDisplayList(PropertyReviewVo leftPropertyReview, PropertyReviewVo rightPropertyReview) {
        return constructDiffDisplayList(leftPropertyReview, rightPropertyReview, diffConsumer());
    }

    public List<DiffDisplay> nonDiffDisplayList(PropertyReviewVo leftPropertyReview, PropertyReviewVo rightPropertyReview) {
        return constructDiffDisplayList(leftPropertyReview, rightPropertyReview, commonConsumer());
    }

    protected List<DiffDisplay> constructDiffDisplayList(PropertyReviewVo leftPropertyReview, PropertyReviewVo rightPropertyReview, BiConsumer<DeltaType, Pair<List<String>, DiffInfo>> originEnricher) {

        //calculate the differences
        List<String> leftProperties = Stream.of(leftPropertyReview.getContent().split(System.lineSeparator())).toList();
        List<String> rightProperties = Stream.of(rightPropertyReview.getContent().split(System.lineSeparator())).toList();
        //calculate the position
        List<String> leftOrigin = Stream.of(leftPropertyReview.getContentOrigin().split(System.lineSeparator())).toList();
        List<String> rightOrigin = Stream.of(rightPropertyReview.getContentOrigin().split(System.lineSeparator())).toList();
        //compute the patch: this is the diffutils part
        Patch<String> patch = DiffUtils.diff(leftProperties, rightProperties, true);
        List<DiffDisplay> displayList = new ArrayList<>();
        //simple output the computed patch to console
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> source = delta.getSource();
            Chunk<String> target = delta.getTarget();
            List<String> sourceLines = source.getLines();
            List<String> targetLines = target.getLines();
            int maxValue = Math.max(sourceLines.size(), targetLines.size());
            // int minValue = Math.min(sourceLines.size(), targetLines.size());
            for (int i = 0; i < maxValue; i++) {
                DiffInfo left = new DiffInfo(DiffPosition.LEFT);
                left.setLineNumber(source.getPosition() + 1 + i);
                String leftValue = StringUtils.EMPTY;
                if (sourceLines.size() > i) {
                    leftValue = sourceLines.get(i);
                }

                DiffInfo right = new DiffInfo(DiffPosition.RIGHT);
                right.setLineNumber(target.getPosition() + 1 + i);
                String rightValue = StringUtils.EMPTY;
                if (targetLines.size() > i) {
                    rightValue = targetLines.get(i);
                }
                Pair<String, String> lineDiff = diffLines(leftValue, rightValue);
                left.setValue(lineDiff.getLeft());
                right.setValue(lineDiff.getRight());

                originEnricher.accept(delta.getType(), Pair.of(leftOrigin, left));
                originEnricher.accept(delta.getType(), Pair.of(rightOrigin, right));

                DiffDisplay diffDisplay = new DiffDisplay();
                diffDisplay.setLeft(left);
                diffDisplay.setRight(right);

                displayList.add(diffDisplay);
            }

        }
        return displayList;
    }

    protected BiConsumer<DeltaType, Pair<List<String>, DiffInfo>> commonConsumer() {
        return (type, pair) -> {
            DiffInfo diffInfo = pair.getRight();
            List<String> originList = pair.getLeft();
            diffInfo.setDeltaType(type.name());
            diffInfo.setOrigin(extractOriginFunc.apply(originList, diffInfo));
        };
    }
    /**
     * in diff case, Origin will be set when has different only
     * description DiffService
     * @author EthanCao
     * @since 27/6/25
     */
    protected BiConsumer<DeltaType, Pair<List<String>, DiffInfo>> diffConsumer() {
        return (type, pair) -> {
            DiffInfo diffInfo = pair.getRight();
            List<String> originList = pair.getLeft();
            if (DeltaType.CHANGE.equals(type) || DeltaType.DELETE.equals(type)) {
                if (DiffPosition.LEFT.equals(diffInfo.getPosition())) {
                    diffInfo.setDeltaType(DeltaType.DELETE.name());
                } else {
                    diffInfo.setDeltaType(DeltaType.CHANGE.name());
                }
            } else {
                diffInfo.setDeltaType(type.name());
            }
            diffInfo.setOrigin(extractOriginFunc.apply(originList, diffInfo));
        };
    }

    public Pair<String, String> diffLines(String source, String target) {
        BiFunction<DiffRow.Tag, Boolean, String> oldTag
                = (tag, f) -> f ? "<span class=\"text-danger\">" : "</span>";
        BiFunction<DiffRow.Tag, Boolean, String> newTag
                = (tag, f) -> f ? "<span class=\"text-success\">" : "</span>";

        DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .mergeOriginalRevised(false)
                .inlineDiffByWord(true)
                .oldTag(oldTag)      //introduce markdown style for strikethrough
                .newTag(newTag)     //introduce markdown style for bold
                .build();
        List<DiffRow> rows = generator.generateDiffRows(List.of(source), List.of(target));

        for (DiffRow row : rows) {
            log.debug("|--{}|{}--|", row.getOldLine(), row.getNewLine());
            return Pair.of(row.getOldLine(), row.getNewLine());
        }
        return Pair.of(source, target);
    }
}
