package shark

import shark.operators._

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jline.Completor;
import jline.ArgumentCompletor;
import jline.ArgumentCompletor.ArgumentDelimiter;
import jline.ArgumentCompletor.AbstractArgumentDelimiter;
import jline.ConsoleReader;
import jline.History;
import jline.SimpleCompletor;

import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hadoop.hive.cli.CliDriver
import org.apache.hadoop.hive.cli.CliSessionState
import org.apache.hadoop.hive.cli.OptionsProcessor
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory
import org.apache.commons.lang.StringUtils
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.Driver
import org.apache.hadoop.hive.ql.parse.ParseDriver
import org.apache.hadoop.hive.ql.exec.FunctionRegistry
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.hive.ql.exec.Utilities.StreamPrinter
import org.apache.hadoop.hive.ql.processors.CommandProcessor
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory
import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper
import org.apache.hadoop.hive.shims.ShimLoader
import org.apache.hadoop.hive.metastore.api.FieldSchema
import org.apache.hadoop.hive.metastore.api.Schema

import scala.collection.JavaConversions._

object SharkCliDriver {
  val prompt = "quicksilver"
  def main(args: Array[String]){
    val oproc = new OptionsProcessor();
    if (!oproc.process_stage1(args)) {
      System.exit(1);
    }

    // NOTE: It is critical to do this here so that log4j is reinitialized
    // before any of the other core hive classes are loaded
    SessionState.initHiveLog4j();

    var ss = new CliSessionState(new HiveConf(classOf[SessionState]));
    ss.in = System.in;
    try {
      ss.out = new PrintStream(System.out, true, "UTF-8");
      ss.err = new PrintStream(System.err, true, "UTF-8");
    } catch {
      case e:UnsupportedEncodingException => System.exit(3);
    }

    if (!oproc.process_stage2(ss)) {
      System.exit(2);
    }

    // set all properties specified via command line
    val conf:HiveConf = ss.getConf();
/*    for ( item:Map.Entry[Object, Object] <- ss.cmdProperties.entrySet().toList()) {
      conf.set( item.getKey().asInstanceOf[String],  item.getValue().asInstanceOf[String]);
    }*/

    if (!ShimLoader.getHadoopShims().usesJobShell()) {
      // hadoop-20 and above - we need to augment classpath using hiveconf
      // components
      // see also: code in ExecDriver.java
      var loader = conf.getClassLoader();
      val auxJars = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEAUXJARS);
      if (StringUtils.isNotBlank(auxJars)) {
        loader = Utilities.addToClassPath(loader, StringUtils.split(auxJars, ","));
      }
      conf.setClassLoader(loader);
      Thread.currentThread().setContextClassLoader(loader);
    }
    SessionState.start(ss);

    var cli = new SharkCliDriver();

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(ss);

    if (ss.execString != null) {
      System.exit(cli.processLine(ss.execString));
    }

    try {
      if (ss.fileName != null) {
        System.exit(cli.processFile(ss.fileName));
      }
    } catch {
      case e:FileNotFoundException => 
        System.err.println("Could not open input file for reading. (" + e.getMessage() + ")");
        System.exit(3);
    }

    var reader = new ConsoleReader();
    reader.setBellEnabled(false);
    // reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));
    reader.addCompletor(CliDriver.getCommandCompletor());

    var line:String = "";
    val HISTORYFILE = ".hivehistory";
    val historyFile = System.getProperty("user.home") + File.separator + HISTORYFILE;
    reader.setHistory(new History(new File(historyFile)));
    var ret = 0;

    var prefix = "";
    var curPrompt = SharkCliDriver.prompt;
    line = reader.readLine(curPrompt + "> ")
    while (line != null){
      if (!prefix.equals("")) {
        prefix += '\n';
      }
      if (line.trim().endsWith(";") && !line.trim().endsWith("\\;")) {
        line = prefix + line;
        ret = cli.processLine(line);
        prefix = "";
        curPrompt = SharkCliDriver.prompt;
      } else {
        prefix = prefix + line;
        curPrompt = CliDriver.prompt2;
      }
      line = reader.readLine(curPrompt + "> ")
    }

    System.exit(ret);
  }

}

class SharkCliDriver extends CliDriver{
  private val ss = SessionState.get()
  private val LOG = LogFactory.getLog("CliDriver")
  private val console:LogHelper = new LogHelper(LOG)
  private val conf:Configuration = if (ss != null)  ss.getConf() else new Configuration()
  
  override def processCmd(cmd: String):Int = {    
    val ss:SessionState = SessionState.get();
    val cmd_trimmed:String = cmd.trim();
    val tokens:Array[String] = cmd_trimmed.split("\\s+");
    val cmd_1:String = cmd_trimmed.substring(tokens(0).length()).trim();
    var ret = 0;
    if (cmd_trimmed.toLowerCase().equals("quit") || cmd_trimmed.toLowerCase().equals("exit") || (tokens(0).equalsIgnoreCase("source")) || (cmd_trimmed.startsWith("!")) || (tokens(0).toLowerCase().equals("list"))) {
      super.processCmd(cmd)
    }
    else{
      val proc = CommandProcessorFactory.get(tokens(0), conf.asInstanceOf[HiveConf]);
      if (proc != null) {
        if (proc.isInstanceOf[Driver]) {
          //Use Shark Driver
          val qp = new SharkDriver(conf.asInstanceOf[HiveConf]);
          qp.init()
          val out = ss.out;
          val start:Long = System.currentTimeMillis();
          if (ss.getIsVerbose()) {
            out.println(cmd);
          }

          ret = qp.run(cmd).getResponseCode();
          if (ret != 0) {
            qp.close();
            return ret;
          }

          val res = new ArrayList[String]();
          
          if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_HEADER)) {
            // Print the column names
            var first_col = true;
            val sc = qp.getSchema();
            /*
            for (FieldSchema fs : sc.getFieldSchemas()) {
              if (!first_col) {
                out.print('\t');
              }
              out.print(fs.getName());
              first_col = false;
            }*/
            out.println();
          }
          
          try {
            while (!out.checkError() && qp.getResults(res)) {
              res.foreach(out.println(_))
              res.clear();
            }
          } catch {
            case e:IOException =>
              console.printError("Failed with exception " + e.getClass().getName() + ":"
                + e.getMessage(), "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
              ret = 1;
          }

          val cret = qp.close();
          if (ret == 0) {
            ret = cret;
          }

          val end:Long = System.currentTimeMillis();
          if (end > start) {
            val timeTaken:Double = (end - start) / 1000.0;
            console.printInfo("Time taken: " + timeTaken + " seconds", null);
          }

        } else {
          if (ss.getIsVerbose()) {
            ss.out.println(tokens(0) + " " + cmd_1);
          }
          ret = proc.run(cmd_1).getResponseCode();
        }
      }
    }
    ret
  }
}



