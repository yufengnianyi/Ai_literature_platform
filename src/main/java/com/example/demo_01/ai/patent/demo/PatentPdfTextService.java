package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageReadStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class PatentPdfTextService {

    public int pageCount(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
    }

    public List<PatentPageText> extractAllPages(Path pdfPath) throws IOException {
        int pageCount = pageCount(pdfPath);
        Set<Integer> pages = new TreeSet<>();
        for (int page = 1; page <= pageCount; page++) {
            pages.add(page);
        }
        return extractPages(pdfPath, pages);
    }

    public List<PatentPageText> extractPages(Path pdfPath, Collection<Integer> pageNumbers)
            throws IOException {
        Set<Integer> requested = new TreeSet<>(pageNumbers);
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return requested.stream()
                    .filter(page -> page >= 1 && page <= pageCount)
                    .map(page -> extractPage(document, stripper, page))
                    .toList();
        }
    }

    private PatentPageText extractPage(PDDocument document, PDFTextStripper stripper, int page) {
        try {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document);
            PatentPageReadStatus status = text == null || text.isBlank()
                    ? PatentPageReadStatus.EMPTY : PatentPageReadStatus.READ_OK;
            return new PatentPageText(page, text, status, null);
        } catch (IOException e) {
            return new PatentPageText(page, "", PatentPageReadStatus.READ_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
