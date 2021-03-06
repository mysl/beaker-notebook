package com.twosigma.beaker.r.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.fluent.Request;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.RList;
import org.rosuda.REngine.Rserve.RConnection;
import org.rosuda.REngine.Rserve.RserveException;

import com.twosigma.beaker.jvm.object.SimpleEvaluationObject;
import com.twosigma.beaker.jvm.object.SimpleEvaluationObject.EvaluationStatus;
import com.twosigma.beaker.jvm.object.TableDisplay;
import com.twosigma.beaker.jvm.serialization.BeakerObjectConverter;
import com.twosigma.beaker.r.module.ErrorGobbler;
import com.twosigma.beaker.r.module.ROutputHandler;


public class RServerEvaluator {
  protected static final String BEGIN_MAGIC = "**beaker_begin_magic**";
  protected static final String END_MAGIC = "**beaker_end_magic**";
  private final static Logger logger = Logger.getLogger(RServerEvaluator.class.getName());

  protected final String shellId;
  protected final String sessionId;
  protected boolean exit;
  protected workerThread myWorker;
  private int corePort;

  protected class jobDescriptor {
    String codeToBeExecuted;
    SimpleEvaluationObject outputObject;

    jobDescriptor(String c , SimpleEvaluationObject o) {
      codeToBeExecuted = c;
      outputObject = o;
    }
  }

  protected final Semaphore syncObject = new Semaphore(0, true);
  protected final ConcurrentLinkedQueue<jobDescriptor> jobQueue = new ConcurrentLinkedQueue<jobDescriptor>();
  protected boolean iswindows;
  protected BeakerObjectConverter objSerializer;

  public RServerEvaluator(String id, String sId, int cp, BeakerObjectConverter os) {
    logger.fine("created");
    shellId = id;
    sessionId = sId;
    exit = false;
    corePort = cp;
    iswindows = System.getProperty("os.name").contains("Windows");
    objSerializer = os;
    startWorker();
  }

  protected void startWorker() {
    myWorker = new workerThread();
    myWorker.start();
    logger.fine("worker started");
  }

  public String getShellId() { return shellId; }

  public void cancelExecution() {
    logger.fine("cancelling");
    myWorker.cancelExecution();
  }

  public void exit() {
    logger.fine("exiting");
    exit = true;
    cancelExecution();
    syncObject.release();
  }

  public void evaluate(SimpleEvaluationObject seo, String code) {
    logger.fine("evaluating");
    // send job to thread
    jobQueue.add(new jobDescriptor(code,seo));
    syncObject.release();
  }

  public List<String> autocomplete(String code, int caretPosition) {
    logger.fine("not implemented");
    // TODO
    return null;
  }

  protected int getPortFromCore() throws IOException, ClientProtocolException
  {
    String password = System.getenv("beaker_core_password");
    String auth = Base64.encodeBase64String(("beaker:" + password).getBytes("ASCII"));
    String response = Request.Get("http://127.0.0.1:" + corePort + "/rest/plugin-services/getAvailablePort")
        .addHeader("Authorization", "Basic " + auth)
        .execute().returnContent().asString();
    return Integer.parseInt(response);
  }

  protected String makeTemp(String base, String suffix) throws IOException
  {
    File dir = new File(System.getenv("beaker_tmp_dir"));
    File tmp = File.createTempFile(base, suffix, dir);
    if (!iswindows) {
      Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(tmp.toPath(), perms);
    }
    String r = tmp.getAbsolutePath();
    logger.fine("returns "+r);
    return r;
  }

  protected BufferedWriter openTemp(String location) throws UnsupportedEncodingException, FileNotFoundException
  {
    // only in Java :(
    return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(location), "ASCII"));
  }

  protected String writeRserveScript(int port, String password) throws IOException
  {
    String pwlocation = makeTemp("BeakerRserve", ".pwd");
    BufferedWriter bw = openTemp(pwlocation);
    bw.write("beaker " + password + "\n");
    bw.close();

    if (iswindows) {
      // R chokes on backslash in windows path, need to quote them
      pwlocation = pwlocation.replace("\\", "\\\\");
    }

    String location = makeTemp("BeakerRserveScript", ".r");
    bw = openTemp(location);
    bw.write("library(Rserve)\n");
    bw.write("run.Rserve(auth=\"required\", plaintext=\"enable\", port=" +
        port + ", pwdfile=\"" + pwlocation + "\")\n");
    bw.close();
    logger.fine("script is "+location);
    return location;
  }


  // Remove the xml version string, and any blank data attributes,
  // since these just cause errors on chrome's console.  Then expand
  // all symbol/use elements manually.  This is because there is a
  // disagreement between firefox and chrome on how to interpret how
  // CSS applies to the resulting hidden DOM elements.  See github
  // Issue #987.  Finally, remove all definitions since they have been
  // expanded and are no longer needed.  This is done with hackey
  // string matching instead of truly parsing the XML.
  protected String fixSvgResults(String xml) {
    Pattern pat = Pattern.compile("<use xlink:href=\"#([^\"]+)\" x=\"([^\"]+)\" y=\"([^\"]+)\"/>");
    xml = xml.replace("d=\"\"", "");
    xml = xml.replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", "");

    while (true) {
      Matcher matcher = pat.matcher(xml);
      if (!matcher.find()) {
        break;
      }
      String expansion = "<g transform=\"translate(" + matcher.group(2) + "," + matcher.group(3) + ")\">\n";
      String glyph = matcher.group(1);
      int gi = xml.indexOf(glyph);
      int pathStart = xml.indexOf("<path", gi);
      int pathStop = xml.indexOf("/>", pathStart);
      String path = xml.substring(pathStart, pathStop + 2);
      expansion = expansion + path + "</g>\n";
      xml = xml.substring(0, matcher.start()) + expansion + xml.substring(matcher.end());
    }

    int defsStart = xml.indexOf("<defs>");
    if (defsStart >= 0) {
      int defsStop = xml.indexOf("</defs>");
      xml = xml.substring(0, defsStart) + xml.substring(defsStop + 7);
    }

    return xml;
  }

  protected boolean addSvgResults(String name, SimpleEvaluationObject obj) {
    File file = new File(name);
    if (file.length() > 0) {
      try (FileInputStream fis = new FileInputStream(file)) {
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        String contents = new String(data, "UTF-8");
        logger.fine("returning svg content");
        obj.finished(fixSvgResults(contents));
        return true;
      } catch (FileNotFoundException e) {
        logger.log(Level.SEVERE,"ERROR reading SVG results",e);
      } catch (IOException e) {
        logger.log(Level.SEVERE,"ERROR reading SVG results",e);
      }
    }
    return false;
  }

  protected boolean isError(REXP result, SimpleEvaluationObject obj) {
    try {
      REXP value = result.asList().at(0);
      if (value.inherits("try-error")) {
        String prefix = "Error in try({ : ";
        String rs = value.asString();
        if (rs.substring(0, prefix.length()).equals(prefix)) {
          rs = rs.substring(prefix.length());
        }
        logger.fine("is an error");
        obj.error(rs);
        return true;
      }
    } catch (REXPMismatchException e) {
    } catch (NullPointerException e) {
    }
    return false;
  }

  protected boolean isVisible(REXP result, SimpleEvaluationObject obj) {
    try {
      int[] asInt = result.asList().at(1).asIntegers();
      if (asInt.length == 1 && asInt[0] != 0) {
        logger.fine("is visible");
        return true;
      }
    } catch (REXPMismatchException e) {
    } catch (NullPointerException e) {
    }
    return false;
  }

  protected boolean isDataFrame(REXP result, SimpleEvaluationObject obj) {
    TableDisplay table;
    try {
      RList list = result.asList().at(0).asList();
      int cols = list.size();
      String[] names = list.keys();
      if (null == names) {
        return false;
      }
      String[][] array = new String[cols][];
      List<List<?>> values = new ArrayList<>();
      List<String> classes = new ArrayList<>();

      for (int i = 0; i < cols; i++) {
        if (null == list.at(i)) {
          return false;
        }
        REXP o = list.at(i);
        String cname = o.getClass().getName();
        classes.add(objSerializer.convertType(cname));
        array[i] = o.asStrings();
      }
      if (array.length < 1) {
        return false;
      }
      for (int j = 0; j < array[0].length; j++) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < cols; i++) {
          if (array[i].length != array[0].length) {
            return false;
          }
          row.add(array[i][j]);
        }
        values.add(row);
      }
      table = new TableDisplay(values, Arrays.asList(names), classes);
    } catch (NullPointerException e) {
      return false;
    } catch (REXPMismatchException e) {
      return false;
    }
    logger.fine("is an datatable");
    obj.finished(table);
    return true;
  }

  protected class workerThread extends Thread {
    RConnection connection;
    ROutputHandler outputHandler;
    ErrorGobbler errorGobbler;
    int port;
    String password;
    int pid;
    Process rServe;

    public workerThread() {
      super("groovy worker");
    }

    public void cancelExecution() {
      if (iswindows) {
        return;
      }
      if (pid >=0) {
        try {
          logger.fine("sending signal");
          Runtime.getRuntime().exec("kill -SIGINT " + pid);
        } catch (IOException e) {
          logger.log(Level.SEVERE, "exception sending signal: ", e);
        }
      }
    }

    private boolean startRserve()
    {
      pid = -1;
      try {
        port = getPortFromCore();
        password  = RandomStringUtils.random(40, true, true);
        String[] command = {"Rscript", writeRserveScript(port, password)};

        // TODO: better error handling

        // Need to clear out some environment variables in order for a
        // new Java process to work correctly.
        // XXX not always necessary, use getPluginEnvps from BeakerConfig?
        // or just delete?
        List<String> environmentList = new ArrayList<>();
        for (Entry<String, String> entry : System.getenv().entrySet()) {
          if (!("CLASSPATH".equals(entry.getKey()))) {
            environmentList.add(entry.getKey() + "=" + entry.getValue());
          }
        }
        String[] environmentArray = new String[environmentList.size()];
        environmentList.toArray(environmentArray);

        rServe = Runtime.getRuntime().exec(command, environmentArray);
        BufferedReader rServeOutput = new BufferedReader(new InputStreamReader(rServe.getInputStream(), "ASCII"));
        String line = null;
        while ((line = rServeOutput.readLine()) != null) {
          if (line.indexOf("(This session will block until Rserve is shut down)") >= 0) {
            break;
          } else {
            // System.out.println("Rserve>" + line);
          }
        }
        errorGobbler = new ErrorGobbler(rServe.getErrorStream());
        errorGobbler.start();

        outputHandler = new ROutputHandler(rServe.getInputStream(), BEGIN_MAGIC, END_MAGIC);
        outputHandler.start();

        connection = new RConnection("127.0.0.1", port);
        connection.login("beaker", password);
        
        pid = connection.eval("Sys.getpid()").asInteger();

        String initCode = "devtools::load_all(Sys.getenv('beaker_r_init'), " +
            "quiet=TRUE, export_all=FALSE)\n" +
            "beaker:::set_session('" + sessionId + "')\n";
        connection.eval(initCode);
      } catch(Exception e) {
        logger.log(Level.SEVERE, "exception starting RServe", e);
        if (rServe!=null) {
          rServe.destroy();
          try {
            rServe.waitFor();
          } catch (InterruptedException e1) {
            e1.printStackTrace();
          }
        }
        connection = null;
        errorGobbler = null;
        outputHandler = null;
        return false;
      }
      return true;
    }

    /*
     * This thread performs all the evaluation
     */

    public void run() {
      jobDescriptor j = null;

      while(!exit) {
        try {
          // wait for work
          syncObject.acquire();

          // get next job descriptor
          j = jobQueue.poll();
          if(j==null)
            continue;

          if (connection==null) {
            if (!startRserve()) {
              j.outputObject.error("... R language backend failed!");
              continue;
            }
          }

          outputHandler.reset(j.outputObject);
          errorGobbler.reset(j.outputObject);

          String file = iswindows ? "rplot.svg" : makeTemp("rplot", ".svg");
          try {
            java.nio.file.Path p = java.nio.file.Paths.get(file);
            java.nio.file.Files.deleteIfExists(p);
          } catch (IOException e) {
            // ignore
          }

          boolean isfinished = false;

          try {
            // direct graphical output
            String tryCode;
            connection.eval("do.call(svg,c(list('" + file + "'), beaker::saved_svg_options))");
            tryCode = "beaker_eval_=withVisible(try({" + j.codeToBeExecuted + "\n},silent=TRUE))";
            REXP result = connection.eval(tryCode);
                        
            if (result!= null)
              logger.finest("RESULT: "+result);
            
            if (null == result) {
              logger.fine("null result");;
              j.outputObject.finished("");
              isfinished = true;
            } else if (isError(result, j.outputObject)) {
              isfinished = true;
            } else if (isDataFrame(result, j.outputObject)) {
              isfinished = true;
              // nothing
            } else if (!isVisible(result, j.outputObject)) {
              logger.fine("is not visible");
            } else {
              logger.fine("capturing from output handler");
              String finish = "print(\"" + BEGIN_MAGIC + "\")\n" +
                  "print(beaker_eval_$value)\n" +
                  "print(\"" + END_MAGIC + "\")\n";
              connection.eval(finish);
              outputHandler.waitForCapture();
              isfinished = (j.outputObject.getStatus() == EvaluationStatus.FINISHED);
            }
          } catch (RserveException e) {
            isfinished = true;
            if (127 == e.getRequestReturnCode()) {
              j.outputObject.error("Interrupted");
            } else {
              j.outputObject.error(e.getMessage());
            }
          }

          
          // flush graphical output
          try {
            connection.eval("dev.off()");
          } catch (RserveException e) {
            if (!isfinished)
              j.outputObject.error("from dev.off(): " + e.getMessage());
          }

          if (!isfinished)
            isfinished = addSvgResults(file, j.outputObject);
          if (!isfinished)
            j.outputObject.finished("");

          outputHandler.reset(null);
          errorGobbler.reset(null);

        } catch(Throwable e) {
          logger.log(Level.SEVERE, "exception in worker:", e);
        }
      }
      logger.fine("destroying worker");
      if (rServe!=null && connection!=null) {
        try {
          connection.shutdown();
        } catch (RserveException e) {
        }
        try {
          rServe.waitFor();
        } catch (InterruptedException e) {
          logger.log(Level.SEVERE, "exception waiting for process termination", e);
        }
      }
      logger.info("DONE");
    }  
  }
}
