package lu.uni.clion.cproject;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * This action allows to transform the default C++ project created by CLion into a dummy C project.
 * <p>
 * It adapts the CMakeLists.txt file, removes the default main.cpp file and creates a "Hello World" C file.
 *
 * @author Christian Grévisse, University of Luxembourg
 * @version 1.0 (10/04/2016)
 */
public class TransformAction extends AnAction {

    /**
     * Name of default C++ main file, created by CLion.
     */
    private static final String DEFAULT_CPP_MAIN_FILE = "main.cpp";

    /**
     * Name of dummy C main file.
     */
    private static final String DEFAULT_C_MAIN_FILE = "main.c";

    /**
     * Name of CMake file.
     */
    private static final String CMAKE = "CMakeLists.txt";

    /**
     * Content for dummy C file ("Hello World" code).
     */
    private static final String DEFAULT_C_HELLO_WORLD_CONTENT = "#include <stdio.h>\n\nint main() {\n\tprintf(\"Hello, World!\\n\");\n\treturn 0;\n}";

    /**
     * Additional flags to be added in the CMake file.
     */
    private static final String ADDITIONAL_FLAGS = "-Wall -Werror";

    /**
     * Currently considered project.
     */
    private Project project;

    /**
     * @return The source files of the considered project.
     */
    private VirtualFile[] sourceRoots() {
        return ProjectRootManager.getInstance(this.project).getContentSourceRoots();
    }

    /**
     * This method finds a virtual file by its name.
     *
     * @param fileName The name of the searched file.
     * @return The {@link VirtualFile} if a file with {@code fileName} exists, otherwise {@code null}.
     */
    private VirtualFile findVirtualFileByName(String fileName) {
        for (VirtualFile file : this.sourceRoots()) {
            if (file.getName().equals(fileName)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Deletes a virtual file.
     *
     * @param virtualFile The file to be deleted.
     * @param requester   The control object requesting the deletion.
     */
    private void deleteVirtualFile(VirtualFile virtualFile, Object requester) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                virtualFile.delete(requester);
            } catch (IOException e1) {
                Messages.showErrorDialog(String.format("The file %s could not be deleted.", virtualFile.getName()), "Error");
            }
        });
    }

    /**
     * @return A map with replacements to be done within the CMake file.
     */
    private static Map<String, String> flagReplacements() {
        Map<String, String> replacements = new HashMap<>();

        replacements.put(TransformAction.DEFAULT_CPP_MAIN_FILE, TransformAction.DEFAULT_C_MAIN_FILE);
        replacements.put("CMAKE_CXX_FLAGS", "CMAKE_C_FLAGS");
        replacements.put("-std=c\\+\\+11", "-std=c99");

        return replacements;
    }

    /**
     * @return The {@link Document} of the CMake file if it exists, otherwise {@code null}.
     */
    private Document getCMakeFile() {
        VirtualFile virtualFile = this.findVirtualFileByName(TransformAction.CMAKE);

        if (virtualFile != null) {
            return FileDocumentManager.getInstance().getDocument(virtualFile);
        }

        return null;
    }

    /**
     * This method makes the necessary transformations to the CMake file, if it exists.
     */
    private void transformCMakeFile() {

        // Retrieve the document object for the CMake file.
        Document cmakeDocument = this.getCMakeFile();

        if (cmakeDocument != null) {
            WriteCommandAction.runWriteCommandAction(this.project, () -> {
                String cmake = cmakeDocument.getText();

                // Make the necessary replacements
                for (Map.Entry<String, String> entry : TransformAction.flagReplacements().entrySet()) {
                    cmake = cmake.replaceAll(entry.getKey(), entry.getValue());
                }

                // Add additional flags
                cmake = cmake.replaceAll("-std", TransformAction.ADDITIONAL_FLAGS + " -std");

                // Set the new text of the document.
                cmakeDocument.setText(cmake);
            });
        }
    }

    /**
     * Creates a dummy C file (if it does not exist yet).
     *
     * @param path The path the file will be saved to.
     */
    private void createCFile(String path) {
        File cHelloWorldFile = new File(path, TransformAction.DEFAULT_C_MAIN_FILE);
        if (!cHelloWorldFile.exists()) {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try (BufferedWriter output = new BufferedWriter(new FileWriter(cHelloWorldFile))) {
                    output.write(TransformAction.DEFAULT_C_HELLO_WORLD_CONTENT);
                } catch (FileNotFoundException e1) {
                    Messages.showErrorDialog("File could not be created.", "Error");
                } catch (IOException e2) {
                    Messages.showErrorDialog("File could not be written.", "Error");
                } finally {
                    LocalFileSystem.getInstance().refreshWithoutFileWatcher(true);
                }
            });
        }
    }

    /**
     * Checks whether the CMake file exists and is valid, i.e. if the replacements have been performed and the additional flags have been put.
     *
     * @return True if the CMake file exists and is valid, false otherwise.
     */
    private boolean isCMakeFileValid() {
        // Retrieve the document object for the CMake file.
        Document cmakeDocument = this.getCMakeFile();

        if (cmakeDocument != null) {
            String cmake = cmakeDocument.getText();

            // Check for replacements
            for (Map.Entry<String, String> entry : TransformAction.flagReplacements().entrySet()) {
                if (cmake.contains(entry.getKey()) && !cmake.contains(entry.getValue())) {
                    return false;
                }
            }

            // Check for additional flags
            for (String additionalFlag : TransformAction.ADDITIONAL_FLAGS.split(" ")) {
                if (!cmake.contains(additionalFlag)) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    /**
     * Checks whether the current project is a valid C project, i.e. whether there is no C++ main file, rather than a C main file and an appropriate CMake file.
     *
     * @return True if the project is a valid C project, false otherwise.
     */
    private boolean isCProject() {

        // If there are no source files, the project cannot be qualified as valid.
        if (this.sourceRoots().length == 0) return false;

        // There should be no main C++ file created by CLion.
        boolean mainCPPFileNotExists = this.findVirtualFileByName(TransformAction.DEFAULT_CPP_MAIN_FILE) == null;

        // There should be a main C file.
        boolean mainCFileExists = new File(this.sourceRoots()[0].getParent().getPath(), TransformAction.DEFAULT_C_MAIN_FILE).exists();

        // The CMake file should be valid.
        boolean cMakeFileValid = this.isCMakeFileValid();

        return mainCPPFileNotExists && mainCFileExists && cMakeFileValid;
    }

    /**
     * Handler for this action.
     * <p>
     * Makes the necessary transformations in order to have a valid C project.
     *
     * @param e Provides information on the action event, such as the considered project.
     */
    @Override
    public void actionPerformed(AnActionEvent e) {

        // Retrieve the current project
        this.project = e.getData(CommonDataKeys.PROJECT);

        if (this.project != null) {

            // Check whether the transformations are necessary.
            if (this.isCProject()) {
                Messages.showErrorDialog("The project is already a C project.", "Error");
                return;
            }

            // Warning message about project transformations.
            int result = Messages.showYesNoDialog(this.project, "Please confirm your intention to transform this project into a C project. This will delete the boilerplate C++ main file, create a dummy C main file and change the CMakeLists file.", "Confirm this action", "Confirm", "Cancel", Messages.getWarningIcon());

            if (result == 0) {
                VirtualFile[] sourceRoots = this.sourceRoots();

                if (sourceRoots.length > 0) {

                    // remove main.cpp file
                    VirtualFile cppMainFile = this.findVirtualFileByName(TransformAction.DEFAULT_CPP_MAIN_FILE);
                    if (cppMainFile != null) {
                        this.deleteVirtualFile(cppMainFile, e);
                    }

                    // replace CMake
                    this.transformCMakeFile();

                    // create main.c
                    this.createCFile(sourceRoots[0].getParent().getPath());
                    
                } else {
                    Messages.showErrorDialog("There are no source files registered for this project.", "Error");
                }
            }
        }
    }
}