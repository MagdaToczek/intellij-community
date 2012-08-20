package com.jetbrains.env.python.debug;

import com.google.common.collect.Sets;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.impl.XSourcePositionImpl;
import com.jetbrains.django.util.VirtualFileUtil;
import com.jetbrains.python.console.PythonDebugLanguageConsoleView;
import com.jetbrains.python.debugger.PyDebugProcess;
import com.jetbrains.python.debugger.PyDebugValue;
import com.jetbrains.python.debugger.PyDebuggerException;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * @author traff
 */
public abstract class PyBaseDebuggerTask extends PyExecutionFixtureTestTask {
  private Set<Pair<String, Integer>> myBreakpoints = Sets.newHashSet();
  protected PyDebugProcess myDebugProcess;
  protected XDebugSession mySession;
  protected Semaphore myPausedSemaphore;
  protected Semaphore myTerminateSemaphore;
  protected boolean shouldPrintOutput = false;
  protected boolean myProcessCanTerminate;

  protected void waitForPause() throws InterruptedException, InvocationTargetException {
    Assert.assertTrue("Debugger didn't stopped within timeout\nOutput:" + output(), waitFor(myPausedSemaphore));

    XDebuggerTestUtil.waitForSwing();
  }

  protected void waitForTerminate() throws InterruptedException, InvocationTargetException {
    setProcessCanTerminate(true);

    Assert.assertTrue("Debugger didn't terminated within timeout\nOutput:" + output(), waitFor(myTerminateSemaphore));
    XDebuggerTestUtil.waitForSwing();
  }

  protected void runToLine(int line) throws InvocationTargetException, InterruptedException {
    XDebugSession currentSession = XDebuggerManager.getInstance(getProject()).getCurrentSession();
    XSourcePosition position = currentSession.getCurrentPosition();


    currentSession.runToPosition(XSourcePositionImpl.create(position.getFile(), line), false);

    waitForPause();
  }

  protected void resume() {
    XDebugSession currentSession = XDebuggerManager.getInstance(getProject()).getCurrentSession();

    Assert.assertTrue(currentSession.isSuspended());
    Assert.assertEquals(0, myPausedSemaphore.availablePermits());

    currentSession.resume();
  }

  protected void stepOver() {
    XDebugSession currentSession = XDebuggerManager.getInstance(getProject()).getCurrentSession();

    Assert.assertTrue(currentSession.isSuspended());
    Assert.assertEquals(0, myPausedSemaphore.availablePermits());

    currentSession.stepOver(false);
  }

  protected void stepInto() {
    XDebugSession currentSession = XDebuggerManager.getInstance(getProject()).getCurrentSession();

    Assert.assertTrue(currentSession.isSuspended());
    Assert.assertEquals(0, myPausedSemaphore.availablePermits());

    currentSession.stepInto();
  }

  protected void smartStepInto(String funcName) {
    XDebugSession currentSession = XDebuggerManager.getInstance(getProject()).getCurrentSession();

    Assert.assertTrue(currentSession.isSuspended());
    Assert.assertEquals(0, myPausedSemaphore.availablePermits());

    myDebugProcess.startSmartStepInto(funcName);
  }

  @NotNull
  protected String output() {
    return XDebuggerTestUtil.getConsoleText(((PythonDebugLanguageConsoleView)mySession.getConsoleView()).getTextConsole());
  }

  @NotNull
  protected void input(String text) {
    PrintWriter pw = new PrintWriter(myDebugProcess.getProcessHandler().getProcessInput());
    pw.println(text);
    pw.flush();
  }

  private void outputContains(String substring) {
    Assert.assertTrue(output().contains(substring));
  }

  public void setProcessCanTerminate(boolean processCanTerminate) {
    myProcessCanTerminate = processCanTerminate;
  }

  protected void clearAllBreakpoints() {

    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        new WriteAction() {
          protected void run(final Result result) {
            XDebuggerTestUtil.removeAllBreakpoints(getProject());
          }
        };
      }
    });
  }

  /**
   * Toggles breakpoint
   *
   * @param file getScriptPath() or path to script
   * @param line starting with 0
   */
  protected void toggleBreakpoint(final String file, final int line) {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        doToggleBreakpoint(file, line);
      }
    });

    if (myBreakpoints.contains(Pair.create(file, line))) {
      myBreakpoints.remove(Pair.create(file, line));
    }
    else {
      myBreakpoints.add(Pair.create(file, line));
    }
  }

  public boolean canPutBreakpointAt(Project project, String file, int line) {
    VirtualFile vFile = VirtualFileUtil.findFile(file);
    Assert.assertNotNull(vFile);
    return XDebuggerUtil.getInstance().canPutBreakpointAt(project, vFile, line);
  }

  private void doToggleBreakpoint(String file, int line) {
    Assert.assertTrue(canPutBreakpointAt(getProject(), file, line));
    XDebuggerTestUtil.toggleBreakpoint(getProject(), VirtualFileUtil.findFile(file), line);
  }

  protected Variable eval(String name) throws InterruptedException {
    Assert.assertTrue("Eval works only while suspended", mySession.isSuspended());
    XValue var = XDebuggerTestUtil.evaluate(mySession, name).first;
    return new Variable(var);
  }

  protected void setVal(String name, String value) throws InterruptedException, PyDebuggerException {
    XValue var = XDebuggerTestUtil.evaluate(mySession, name).first;
    myDebugProcess.changeVariable((PyDebugValue)var, value);
  }

  public void waitForOutput(String string) throws InterruptedException {
    int count = 0;
    while (!output().contains(string)) {
      if (count > 10) {
        Assert.fail("'" + string + "'" + " is not present in output.\n" + output());
      }
      Thread.sleep(2000);
      count++;
    }
  }

  public void setShouldPrintOutput(boolean shouldPrintOutput) {
    this.shouldPrintOutput = shouldPrintOutput;
  }

  @Override
  public void setUp() throws Exception {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        try {
          if (myFixture == null) {
            PyBaseDebuggerTask.super.setUp();
          }
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  @Override
  public void tearDown() throws Exception {
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      public void run() {
        try {
          if (mySession != null) {
            finishSession();
          }
          PyBaseDebuggerTask.super.tearDown();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  protected void finishSession() throws InterruptedException {
    disposeDebugProcess();

    if (mySession != null) {
      new WriteAction() {
        protected void run(Result result) throws Throwable {
          mySession.stop();
        }
      }.execute();

      XDebuggerTestUtil.disposeDebugSession(mySession);
      mySession = null;
      myDebugProcess = null;
      myPausedSemaphore = null;
    }
  }

  protected abstract void disposeDebugProcess() throws InterruptedException;

  protected static class Variable {
    private final XTestValueNode myValueNode;

    public Variable(XValue value) throws InterruptedException {
      myValueNode = XDebuggerTestUtil.computePresentation(value);
    }

    public Variable hasValue(String value) {
      Assert.assertEquals(value, myValueNode.myValue);
      return this;
    }

    public Variable hasType(String type) {
      Assert.assertEquals(type, myValueNode.myType);
      return this;
    }

    public Variable hasName(String name) {
      Assert.assertEquals(name, myValueNode.myName);
      return this;
    }
  }

  protected class OutputPrinter {
    private Thread myThread;

    public void start() {
      myThread = new Thread(new Runnable() {
        @Override
        public void run() {
          doJob();
        }
      });
      myThread.setDaemon(true);
      myThread.start();
    }

    private void doJob() {
      int len = 0;
      try {
        while (true) {
          String s = output();
          if (s.length() > len) {
            System.out.print(s.substring(len));
          }
          len = s.length();

          Thread.sleep(500);
        }
      }
      catch (Exception e) {
      }
    }

    public void stop() {
      myThread.interrupt();
    }
  }
}
