/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.filter.WhileMatchFilter;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Hash;
import org.apache.hadoop.hbase.util.MurmurHash;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * Script used evaluating HBase performance and scalability.  Runs a HBase
 * client that steps through one of a set of hardcoded tests or 'experiments'
 * (e.g. a random reads test, a random writes test, etc.). Pass on the
 * command-line which test to run and how many clients are participating in
 * this experiment. Run <code>java PerformanceEvaluation --help</code> to
 * obtain usage.
 *
 * <p>This class sets up and runs the evaluation programs described in
 * Section 7, <i>Performance Evaluation</i>, of the <a
 * href="http://labs.google.com/papers/bigtable.html">Bigtable</a>
 * paper, pages 8-10.
 *
 * <p>If number of clients > 1, we start up a MapReduce job. Each map task
 * runs an individual client. Each client does about 1GB of data.
 */
public class PerformanceEvaluation extends Configured implements Tool {
  protected static final Log LOG = LogFactory.getLog(PerformanceEvaluation.class.getName());

  public static final TableName TABLE_NAME = TableName.valueOf("TestTable");
  public static final byte[] FAMILY_NAME = Bytes.toBytes("info");
  public static final byte[] QUALIFIER_NAME = Bytes.toBytes("data");
  public static final int VALUE_LENGTH = 1000;
  public static final int ROW_LENGTH = 26;

  private static final int ONE_GB = 1024 * 1024 * 1000;
  private static final int ROWS_PER_GB = ONE_GB / VALUE_LENGTH;
  // TODO : should we make this configurable
  private static final int TAG_LENGTH = 256;
  private static final DecimalFormat FMT = new DecimalFormat("0.##");
  private static final MathContext CXT = MathContext.DECIMAL64;
  private static final BigDecimal MS_PER_SEC = BigDecimal.valueOf(1000);
  private static final BigDecimal BYTES_PER_MB = BigDecimal.valueOf(1024 * 1024);

  protected HTableDescriptor TABLE_DESCRIPTOR;
  protected Map<String, CmdDescriptor> commands = new TreeMap<String, CmdDescriptor>();

  private boolean nomapred = false;
  private int N = 1;
  private int R = ROWS_PER_GB;
  private float sampleRate = 1.0f;
  private TableName tableName = TABLE_NAME;
  private Compression.Algorithm compression = Compression.Algorithm.NONE;
  private DataBlockEncoding blockEncoding = DataBlockEncoding.NONE;
  private boolean flushCommits = true;
  private boolean writeToWAL = true;
  private boolean inMemoryCF = false;
  private boolean reportLatency = false;
  private int presplitRegions = 0;
  private boolean useTags = false;
  private int noOfTags = 1;
  private int multiGet = 0;
  private HConnection connection;

  private static final Path PERF_EVAL_DIR = new Path("performance_evaluation");

  /** Regex to parse lines in input file passed to mapreduce task. */
  public static final Pattern LINE_PATTERN =
    Pattern.compile("tableName=(\\w+),\\s+" +
        "startRow=(\\d+),\\s+" +
        "perClientRunRows=(\\d+),\\s+" +
        "totalRows=(\\d+),\\s+" +
        "sampleRate=([-+]?[0-9]*\\.?[0-9]+),\\s+" +
        "clients=(\\d+),\\s+" +
        "flushCommits=(\\w+),\\s+" +
        "writeToWAL=(\\w+),\\s+" +
        "useTags=(\\w+),\\s+" +
        "noOfTags=(\\d+),\\s+" +
        "reportLatency=(\\w+),\\s+" +
        "multiGet=(\\d+)");

  /**
   * Enum for map metrics.  Keep it out here rather than inside in the Map
   * inner-class so we can find associated properties.
   */
  protected static enum Counter {
    /** elapsed time */
    ELAPSED_TIME,
    /** number of rows */
    ROWS
  }

  /**
   * Constructor
   * @param conf Configuration object
   */
  public PerformanceEvaluation(final Configuration conf) {
    super(conf);

    addCommandDescriptor(RandomReadTest.class, "randomRead",
        "Run random read test");
    addCommandDescriptor(RandomSeekScanTest.class, "randomSeekScan",
        "Run random seek and scan 100 test");
    addCommandDescriptor(RandomScanWithRange10Test.class, "scanRange10",
        "Run random seek scan with both start and stop row (max 10 rows)");
    addCommandDescriptor(RandomScanWithRange100Test.class, "scanRange100",
        "Run random seek scan with both start and stop row (max 100 rows)");
    addCommandDescriptor(RandomScanWithRange1000Test.class, "scanRange1000",
        "Run random seek scan with both start and stop row (max 1000 rows)");
    addCommandDescriptor(RandomScanWithRange10000Test.class, "scanRange10000",
        "Run random seek scan with both start and stop row (max 10000 rows)");
    addCommandDescriptor(RandomWriteTest.class, "randomWrite",
        "Run random write test");
    addCommandDescriptor(SequentialReadTest.class, "sequentialRead",
        "Run sequential read test");
    addCommandDescriptor(SequentialWriteTest.class, "sequentialWrite",
        "Run sequential write test");
    addCommandDescriptor(ScanTest.class, "scan",
        "Run scan test (read every row)");
    addCommandDescriptor(FilteredScanTest.class, "filterScan",
        "Run scan test using a filter to find a specific row based on it's value " +
        "(make sure to use --rows=20)");
  }

  protected void addCommandDescriptor(Class<? extends Test> cmdClass,
      String name, String description) {
    CmdDescriptor cmdDescriptor =
      new CmdDescriptor(cmdClass, name, description);
    commands.put(name, cmdDescriptor);
  }

  /**
   * Implementations can have their status set.
   */
  interface Status {
    /**
     * Sets status
     * @param msg status message
     * @throws IOException
     */
    void setStatus(final String msg) throws IOException;
  }

  /**
   *  This class works as the InputSplit of Performance Evaluation
   *  MapReduce InputFormat, and the Record Value of RecordReader.
   *  Each map task will only read one record from a PeInputSplit,
   *  the record value is the PeInputSplit itself.
   */
  public static class PeInputSplit extends InputSplit implements Writable {
    private TableName tableName = TABLE_NAME;
    private int startRow = 0;
    private int rows = 0;
    private int totalRows = 0;
    private float sampleRate = 1.0f;
    private int clients = 0;
    private boolean flushCommits = false;
    private boolean writeToWAL = true;
    private boolean useTags = false;
    private int noOfTags = 0;
    private boolean reportLatency = false;
    private int multiGet = 0;

    public PeInputSplit() {}

    public PeInputSplit(TableName tableName, int startRow, int rows, int totalRows,
        float sampleRate, int clients, boolean flushCommits, boolean writeToWAL,
        boolean useTags, int noOfTags, boolean reportLatency, int multiGet) {
      this.tableName = tableName;
      this.startRow = startRow;
      this.rows = rows;
      this.totalRows = totalRows;
      this.sampleRate = sampleRate;
      this.clients = clients;
      this.flushCommits = flushCommits;
      this.writeToWAL = writeToWAL;
      this.useTags = useTags;
      this.noOfTags = noOfTags;
      this.reportLatency = reportLatency;
      this.multiGet = multiGet;
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      int tableNameLen = in.readInt();
      byte[] name = new byte[tableNameLen];
      in.readFully(name);
      this.tableName = TableName.valueOf(name);

      this.startRow = in.readInt();
      this.rows = in.readInt();
      this.totalRows = in.readInt();
      this.sampleRate = in.readFloat();
      this.clients = in.readInt();
      this.flushCommits = in.readBoolean();
      this.writeToWAL = in.readBoolean();
      this.useTags = in.readBoolean();
      this.noOfTags = in.readInt();
      this.reportLatency = in.readBoolean();
      this.multiGet = in.readInt();
    }

    @Override
    public void write(DataOutput out) throws IOException {
      byte[] name = this.tableName.toBytes();
      out.writeInt(name.length);
      out.write(name);
      out.writeInt(startRow);
      out.writeInt(rows);
      out.writeInt(totalRows);
      out.writeFloat(sampleRate);
      out.writeInt(clients);
      out.writeBoolean(flushCommits);
      out.writeBoolean(writeToWAL);
      out.writeBoolean(useTags);
      out.writeInt(noOfTags);
      out.writeBoolean(reportLatency);
      out.writeInt(multiGet);
    }

    @Override
    public long getLength() throws IOException, InterruptedException {
      return 0;
    }

    @Override
    public String[] getLocations() throws IOException, InterruptedException {
      return new String[0];
    }

    public TableName getTableName() {
      return tableName;
    }

    public int getStartRow() {
      return startRow;
    }

    public int getRows() {
      return rows;
    }

    public int getTotalRows() {
      return totalRows;
    }

    public float getSampleRate() {
      return sampleRate;
    }

    public int getClients() {
      return clients;
    }

    public boolean isFlushCommits() {
      return flushCommits;
    }

    public boolean isWriteToWAL() {
      return writeToWAL;
    }

    public boolean isUseTags() {
      return useTags;
    }

    public int getNoOfTags() {
      return noOfTags;
    }

    public boolean isReportLatency() {
      return reportLatency;
    }

    public int getMultiGet() {
      return multiGet;
    }
  }

  /**
   *  InputFormat of Performance Evaluation MapReduce job.
   *  It extends from FileInputFormat, want to use it's methods such as setInputPaths().
   */
  public static class PeInputFormat extends FileInputFormat<NullWritable, PeInputSplit> {

    @Override
    public List<InputSplit> getSplits(JobContext job) throws IOException {
      // generate splits
      List<InputSplit> splitList = new ArrayList<InputSplit>();

      for (FileStatus file: listStatus(job)) {
        if (file.isDir()) {
          continue;
        }
        Path path = file.getPath();
        FileSystem fs = path.getFileSystem(job.getConfiguration());
        FSDataInputStream fileIn = fs.open(path);
        LineReader in = new LineReader(fileIn, job.getConfiguration());
        int lineLen = 0;
        while(true) {
          Text lineText = new Text();
          lineLen = in.readLine(lineText);
          if(lineLen <= 0) {
          break;
          }
          Matcher m = LINE_PATTERN.matcher(lineText.toString());
          if((m != null) && m.matches()) {
            TableName tableName = TableName.valueOf(m.group(1));
            int startRow = Integer.parseInt(m.group(2));
            int rows = Integer.parseInt(m.group(3));
            int totalRows = Integer.parseInt(m.group(4));
            float sampleRate = Float.parseFloat(m.group(5));
            int clients = Integer.parseInt(m.group(6));
            boolean flushCommits = Boolean.parseBoolean(m.group(7));
            boolean writeToWAL = Boolean.parseBoolean(m.group(8));
            boolean useTags = Boolean.parseBoolean(m.group(9));
            int noOfTags = Integer.parseInt(m.group(10));
            boolean reportLatency = Boolean.parseBoolean(m.group(11));
            int multiGet = Integer.parseInt(m.group(12));

            LOG.debug("tableName=" + tableName +
                      " split["+ splitList.size() + "] " +
                      " startRow=" + startRow +
                      " rows=" + rows +
                      " totalRows=" + totalRows +
                      " sampleRate=" + sampleRate +
                      " clients=" + clients +
                      " flushCommits=" + flushCommits +
                      " writeToWAL=" + writeToWAL +
                      " useTags=" + useTags +
                      " noOfTags=" + noOfTags +
                      " reportLatency=" + reportLatency +
                      " multiGet=" + multiGet);

            PeInputSplit newSplit =
              new PeInputSplit(tableName, startRow, rows, totalRows, sampleRate, clients,
                flushCommits, writeToWAL, useTags, noOfTags, reportLatency, multiGet);
            splitList.add(newSplit);
          }
        }
        in.close();
      }

      LOG.info("Total # of splits: " + splitList.size());
      return splitList;
    }

    @Override
    public RecordReader<NullWritable, PeInputSplit> createRecordReader(InputSplit split,
                            TaskAttemptContext context) {
      return new PeRecordReader();
    }

    public static class PeRecordReader extends RecordReader<NullWritable, PeInputSplit> {
      private boolean readOver = false;
      private PeInputSplit split = null;
      private NullWritable key = null;
      private PeInputSplit value = null;

      @Override
      public void initialize(InputSplit split, TaskAttemptContext context)
                  throws IOException, InterruptedException {
        this.readOver = false;
        this.split = (PeInputSplit)split;
      }

      @Override
      public boolean nextKeyValue() throws IOException, InterruptedException {
        if(readOver) {
          return false;
        }

        key = NullWritable.get();
        value = split;

        readOver = true;
        return true;
      }

      @Override
      public NullWritable getCurrentKey() throws IOException, InterruptedException {
        return key;
      }

      @Override
      public PeInputSplit getCurrentValue() throws IOException, InterruptedException {
        return value;
      }

      @Override
      public float getProgress() throws IOException, InterruptedException {
        if(readOver) {
          return 1.0f;
        } else {
          return 0.0f;
        }
      }

      @Override
      public void close() throws IOException {
        // do nothing
      }
    }
  }

  /**
   * MapReduce job that runs a performance evaluation client in each map task.
   */
  public static class EvaluationMapTask
      extends Mapper<NullWritable, PeInputSplit, LongWritable, LongWritable> {

    /** configuration parameter name that contains the command */
    public final static String CMD_KEY = "EvaluationMapTask.command";
    /** configuration parameter name that contains the PE impl */
    public static final String PE_KEY = "EvaluationMapTask.performanceEvalImpl";

    private Class<? extends Test> cmd;
    private PerformanceEvaluation pe;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      this.cmd = forName(context.getConfiguration().get(CMD_KEY), Test.class);

      // this is required so that extensions of PE are instantiated within the
      // map reduce task...
      Class<? extends PerformanceEvaluation> peClass =
          forName(context.getConfiguration().get(PE_KEY), PerformanceEvaluation.class);
      try {
        this.pe = peClass.getConstructor(Configuration.class)
            .newInstance(context.getConfiguration());
      } catch (Exception e) {
        throw new IllegalStateException("Could not instantiate PE instance", e);
      }
    }

    private <Type> Class<? extends Type> forName(String className, Class<Type> type) {
      Class<? extends Type> clazz = null;
      try {
        clazz = Class.forName(className).asSubclass(type);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("Could not find class for name: " + className, e);
      }
      return clazz;
    }

    protected void map(NullWritable key, PeInputSplit value, final Context context)
           throws IOException, InterruptedException {

      Status status = new Status() {
        public void setStatus(String msg) {
           context.setStatus(msg);
        }
      };

      // Evaluation task
      pe.tableName = value.getTableName();
      long elapsedTime = this.pe.runOneClient(this.cmd, value.getStartRow(),
          value.getRows(), value.getTotalRows(), value.getSampleRate(),
          value.isFlushCommits(), value.isWriteToWAL(), value.isUseTags(),
          value.getNoOfTags(), value.isReportLatency(), value.getMultiGet(),
          HConnectionManager.createConnection(context.getConfiguration()), status);
      // Collect how much time the thing took. Report as map output and
      // to the ELAPSED_TIME counter.
      context.getCounter(Counter.ELAPSED_TIME).increment(elapsedTime);
      context.getCounter(Counter.ROWS).increment(value.rows);
      context.write(new LongWritable(value.startRow), new LongWritable(elapsedTime));
      context.progress();
    }
  }

  /*
   * If table does not already exist, create.
   * @param c Client to use checking.
   * @return True if we created the table.
   * @throws IOException
   */
  private boolean checkTable(HBaseAdmin admin) throws IOException {
    HTableDescriptor tableDescriptor = getTableDescriptor();
    if (this.presplitRegions > 0) {
      // presplit requested
      if (admin.tableExists(tableDescriptor.getTableName())) {
        admin.disableTable(tableDescriptor.getTableName());
        admin.deleteTable(tableDescriptor.getTableName());
      }

      byte[][] splits = getSplits();
      for (int i=0; i < splits.length; i++) {
        LOG.debug(" split " + i + ": " + Bytes.toStringBinary(splits[i]));
      }
      admin.createTable(tableDescriptor, splits);
      LOG.info ("Table created with " + this.presplitRegions + " splits");
    }
    else {
      boolean tableExists = admin.tableExists(tableDescriptor.getTableName());
      if (!tableExists) {
        admin.createTable(tableDescriptor);
        LOG.info("Table " + tableDescriptor + " created");
      }
    }
    return admin.tableExists(tableDescriptor.getTableName());
  }

  protected HTableDescriptor getTableDescriptor() {
    if (TABLE_DESCRIPTOR == null) {
      TABLE_DESCRIPTOR = new HTableDescriptor(tableName);
      HColumnDescriptor family = new HColumnDescriptor(FAMILY_NAME);
      family.setDataBlockEncoding(blockEncoding);
      family.setCompressionType(compression);
      if (inMemoryCF) {
        family.setInMemory(true);
      }
      TABLE_DESCRIPTOR.addFamily(family);
    }
    return TABLE_DESCRIPTOR;
  }

  /**
   * generates splits based on total number of rows and specified split regions
   *
   * @return splits : array of byte []
   */
  protected  byte[][] getSplits() {
    if (this.presplitRegions == 0)
      return new byte [0][];

    int numSplitPoints = presplitRegions - 1;
    byte[][] splits = new byte[numSplitPoints][];
    int jump = this.R  / this.presplitRegions;
    for (int i=0; i < numSplitPoints; i++) {
      int rowkey = jump * (1 + i);
      splits[i] = format(rowkey);
    }
    return splits;
  }

  /*
   * We're to run multiple clients concurrently.  Setup a mapreduce job.  Run
   * one map per client.  Then run a single reduce to sum the elapsed times.
   * @param cmd Command to run.
   * @throws IOException
   */
  private void runNIsMoreThanOne(final Class<? extends Test> cmd)
  throws IOException, InterruptedException, ClassNotFoundException {
    checkTable(new HBaseAdmin(getConf()));
    if (this.nomapred) {
      doMultipleClients(cmd);
    } else {
      doMapReduce(cmd);
    }
  }

  /*
   * Run all clients in this vm each to its own thread.
   * @param cmd Command to run.
   * @throws IOException
   */
  private void doMultipleClients(final Class<? extends Test> cmd) throws IOException {
    final List<Thread> threads = new ArrayList<Thread>(this.N);
    final long[] timings = new long[this.N];
    final int perClientRows = R/N;
    final float sampleRate = this.sampleRate;
    final TableName tableName = this.tableName;
    final DataBlockEncoding encoding = this.blockEncoding;
    final boolean flushCommits = this.flushCommits;
    final Compression.Algorithm compression = this.compression;
    final boolean writeToWal = this.writeToWAL;
    final boolean reportLatency = this.reportLatency;
    final int preSplitRegions = this.presplitRegions;
    final boolean useTags = this.useTags;
    final int numTags = this.noOfTags;
    final int multiGet = this.multiGet;
    final HConnection connection = HConnectionManager.createConnection(getConf());
    for (int i = 0; i < this.N; i++) {
      final int index = i;
      Thread t = new Thread ("TestClient-" + i) {
        @Override
        public void run() {
          super.run();
          PerformanceEvaluation pe = new PerformanceEvaluation(getConf());
          pe.tableName = tableName;
          pe.blockEncoding = encoding;
          pe.flushCommits = flushCommits;
          pe.compression = compression;
          pe.writeToWAL = writeToWal;
          pe.presplitRegions = preSplitRegions;
          pe.N = N;
          pe.sampleRate = sampleRate;
          pe.reportLatency = reportLatency;
          pe.connection = connection;
          pe.useTags = useTags;
          pe.noOfTags = numTags;
          pe.multiGet = multiGet;
          try {
            long elapsedTime = pe.runOneClient(cmd, index * perClientRows,
               perClientRows, R, sampleRate, flushCommits, writeToWal, useTags,
               noOfTags, reportLatency, multiGet, connection, new Status() {
                  public void setStatus(final String msg) throws IOException {
                    LOG.info("client-" + getName() + " " + msg);
                  }
                });
            timings[index] = elapsedTime;
            LOG.info("Finished " + getName() + " in " + elapsedTime +
              "ms writing " + perClientRows + " rows");
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };
      threads.add(t);
    }
    for (Thread t: threads) {
      t.start();
    }
    for (Thread t: threads) {
      while(t.isAlive()) {
        try {
          t.join();
        } catch (InterruptedException e) {
          LOG.debug("Interrupted, continuing" + e.toString());
        }
      }
    }
    final String test = cmd.getSimpleName();
    LOG.info("[" + test + "] Summary of timings (ms): "
             + Arrays.toString(timings));
    Arrays.sort(timings);
    long total = 0;
    for (int i = 0; i < this.N; i++) {
      total += timings[i];
    }
    LOG.info("[" + test + "]"
             + "\tMin: " + timings[0] + "ms"
             + "\tMax: " + timings[this.N - 1] + "ms"
             + "\tAvg: " + (total / this.N) + "ms");
  }

  /*
   * Run a mapreduce job.  Run as many maps as asked-for clients.
   * Before we start up the job, write out an input file with instruction
   * per client regards which row they are to start on.
   * @param cmd Command to run.
   * @throws IOException
   */
  private void doMapReduce(final Class<? extends Test> cmd) throws IOException,
        InterruptedException, ClassNotFoundException {
    Configuration conf = getConf();
    Path inputDir = writeInputFile(conf);
    conf.set(EvaluationMapTask.CMD_KEY, cmd.getName());
    conf.set(EvaluationMapTask.PE_KEY, getClass().getName());
    Job job = new Job(conf);
    job.setJarByClass(PerformanceEvaluation.class);
    job.setJobName("HBase Performance Evaluation");

    job.setInputFormatClass(PeInputFormat.class);
    PeInputFormat.setInputPaths(job, inputDir);

    job.setOutputKeyClass(LongWritable.class);
    job.setOutputValueClass(LongWritable.class);

    job.setMapperClass(EvaluationMapTask.class);
    job.setReducerClass(LongSumReducer.class);

    job.setNumReduceTasks(1);

    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, new Path(inputDir.getParent(), "outputs"));

    TableMapReduceUtil.addDependencyJars(job);
    // Add a Class from the hbase.jar so it gets registered too.
    TableMapReduceUtil.addDependencyJars(job.getConfiguration(),
      org.apache.hadoop.hbase.util.Bytes.class);

    TableMapReduceUtil.initCredentials(job);

    job.waitForCompletion(true);
  }

  /*
   * Write input file of offsets-per-client for the mapreduce job.
   * @param c Configuration
   * @return Directory that contains file written.
   * @throws IOException
   */
  private Path writeInputFile(final Configuration c) throws IOException {
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
    Path jobdir = new Path(PERF_EVAL_DIR, formatter.format(new Date()));
    Path inputDir = new Path(jobdir, "inputs");

    FileSystem fs = FileSystem.get(c);
    fs.mkdirs(inputDir);

    Path inputFile = new Path(inputDir, "input.txt");
    PrintStream out = new PrintStream(fs.create(inputFile));
    // Make input random.
    Map<Integer, String> m = new TreeMap<Integer, String>();
    Hash h = MurmurHash.getInstance();
    int perClientRows = (this.R / this.N);
    try {
      for (int i = 0; i < 10; i++) {
        for (int j = 0; j < N; j++) {
          String s = "tableName=" + this.tableName +
          ", startRow=" + ((j * perClientRows) + (i * (perClientRows/10))) +
          ", perClientRunRows=" + (perClientRows / 10) +
          ", totalRows=" + this.R +
          ", sampleRate=" + this.sampleRate +
          ", clients=" + this.N +
          ", flushCommits=" + this.flushCommits +
          ", writeToWAL=" + this.writeToWAL +
          ", useTags=" + this.useTags +
          ", noOfTags=" + this.noOfTags +
          ", reportLatency=" + this.reportLatency +
          ", multiGet=" + this.multiGet;
          int hash = h.hash(Bytes.toBytes(s));
          m.put(hash, s);
        }
      }
      for (Map.Entry<Integer, String> e: m.entrySet()) {
        out.println(e.getValue());
      }
    } finally {
      out.close();
    }
    return inputDir;
  }

  /**
   * Describes a command.
   */
  static class CmdDescriptor {
    private Class<? extends Test> cmdClass;
    private String name;
    private String description;

    CmdDescriptor(Class<? extends Test> cmdClass, String name, String description) {
      this.cmdClass = cmdClass;
      this.name = name;
      this.description = description;
    }

    public Class<? extends Test> getCmdClass() {
      return cmdClass;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }
  }

  /**
   * Wraps up options passed to {@link org.apache.hadoop.hbase.PerformanceEvaluation.Test
   * tests}.  This makes the reflection logic a little easier to understand...
   */
  static class TestOptions {
    private int startRow;
    private int perClientRunRows;
    private int totalRows;
    private float sampleRate;
    private int numClientThreads;
    private TableName tableName;
    private boolean flushCommits;
    private boolean writeToWAL = true;
    private boolean useTags = false;
    private int noOfTags = 0;
    private boolean reportLatency;
    private int multiGet = 0;
    private HConnection connection;

    TestOptions() {}

    TestOptions(int startRow, int perClientRunRows, int totalRows, float sampleRate,
        int numClientThreads, TableName tableName, boolean flushCommits, boolean writeToWAL,
        boolean useTags, int noOfTags, boolean reportLatency, int multiGet,
        HConnection connection) {
      this.startRow = startRow;
      this.perClientRunRows = perClientRunRows;
      this.totalRows = totalRows;
      this.sampleRate = sampleRate;
      this.numClientThreads = numClientThreads;
      this.tableName = tableName;
      this.flushCommits = flushCommits;
      this.writeToWAL = writeToWAL;
      this.useTags = useTags;
      this.noOfTags = noOfTags;
      this.reportLatency = reportLatency;
      this.multiGet = multiGet;
      this.connection = connection;
    }

    public int getStartRow() {
      return startRow;
    }

    public int getPerClientRunRows() {
      return perClientRunRows;
    }

    public int getTotalRows() {
      return totalRows;
    }

    public float getSampleRate() {
      return sampleRate;
    }

    public int getNumClientThreads() {
      return numClientThreads;
    }

    public TableName getTableName() {
      return tableName;
    }

    public boolean isFlushCommits() {
      return flushCommits;
    }

    public boolean isWriteToWAL() {
      return writeToWAL;
    }

    public boolean isReportLatency() {
      return reportLatency;
    }

    public int getMultiGet() {
      return multiGet;
    }

    public HConnection getConnection() {
      return connection;
    }
    
    public boolean isUseTags() {
      return this.useTags;
    }
    public int getNumTags() {
      return this.noOfTags;
    }
  }

  /*
   * A test.
   * Subclass to particularize what happens per row.
   */
  static abstract class Test {
    // Below is make it so when Tests are all running in the one
    // jvm, that they each have a differently seeded Random.
    private static final Random randomSeed =
      new Random(System.currentTimeMillis());
    private static long nextRandomSeed() {
      return randomSeed.nextLong();
    }
    protected final Random rand = new Random(nextRandomSeed());

    protected final int startRow;
    protected final int perClientRunRows;
    protected final int totalRows;
    protected final float sampleRate;
    private final Status status;
    protected TableName tableName;
    protected HTableInterface table;
    protected volatile Configuration conf;
    protected boolean flushCommits;
    protected boolean writeToWAL;
    protected boolean useTags;
    protected int noOfTags;
    protected boolean reportLatency;
    protected HConnection connection;

    /**
     * Note that all subclasses of this class must provide a public contructor
     * that has the exact same list of arguments.
     */
    Test(final Configuration conf, final TestOptions options, final Status status) {
      super();
      this.startRow = options.getStartRow();
      this.perClientRunRows = options.getPerClientRunRows();
      this.totalRows = options.getTotalRows();
      this.sampleRate = options.getSampleRate();
      this.status = status;
      this.tableName = options.getTableName();
      this.table = null;
      this.conf = conf;
      this.flushCommits = options.isFlushCommits();
      this.writeToWAL = options.isWriteToWAL();
      this.useTags = options.isUseTags();
      this.noOfTags = options.getNumTags();
      this.reportLatency = options.isReportLatency();
      this.connection = options.getConnection();
    }

    private String generateStatus(final int sr, final int i, final int lr) {
      return sr + "/" + i + "/" + lr;
    }

    protected int getReportingPeriod() {
      int period = this.perClientRunRows / 10;
      return period == 0 ? this.perClientRunRows : period;
    }

    void testSetup() throws IOException {
      this.table = connection.getTable(tableName);
      this.table.setAutoFlush(false, true);
    }

    void testTakedown() throws IOException {
      if (flushCommits) {
        this.table.flushCommits();
      }
      table.close();
    }

    /*
     * Run test
     * @return Elapsed time.
     * @throws IOException
     */
    long test() throws IOException {
      testSetup();
      LOG.info("Timed test starting in thread " + Thread.currentThread().getName());
      final long startTime = System.nanoTime();
      try {
        testTimed();
      } finally {
        testTakedown();
      }
      return (System.nanoTime() - startTime) / 1000000;
    }

    /**
     * Provides an extension point for tests that don't want a per row invocation.
     */
    void testTimed() throws IOException {
      int lastRow = this.startRow + this.perClientRunRows;
      // Report on completion of 1/10th of total.
      for (int i = this.startRow; i < lastRow; i++) {
        testRow(i);
        if (status != null && i > 0 && (i % getReportingPeriod()) == 0) {
          status.setStatus(generateStatus(this.startRow, i, lastRow));
        }
      }
    }

    /*
    * Test for individual row.
    * @param i Row index.
    */
    abstract void testRow(final int i) throws IOException;
  }


  @SuppressWarnings("unused")
  static class RandomSeekScanTest extends Test {
    RandomSeekScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Scan scan = new Scan(getRandomRow(this.rand, this.totalRows));
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      scan.setFilter(new WhileMatchFilter(new PageFilter(120)));
      ResultScanner s = this.table.getScanner(scan);
      for (Result rr; (rr = s.next()) != null;) ;
      s.close();
    }

    @Override
    protected int getReportingPeriod() {
      int period = this.perClientRunRows / 100;
      return period == 0 ? this.perClientRunRows : period;
    }

  }

  @SuppressWarnings("unused")
  static abstract class RandomScanWithRangeTest extends Test {
    RandomScanWithRangeTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Pair<byte[], byte[]> startAndStopRow = getStartAndStopRow();
      Scan scan = new Scan(startAndStopRow.getFirst(), startAndStopRow.getSecond());
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      ResultScanner s = this.table.getScanner(scan);
      int count = 0;
      for (Result rr; (rr = s.next()) != null;) {
        count++;
      }

      if (i % 100 == 0) {
        LOG.info(String.format("Scan for key range %s - %s returned %s rows",
            Bytes.toString(startAndStopRow.getFirst()),
            Bytes.toString(startAndStopRow.getSecond()), count));
      }

      s.close();
    }

    protected abstract Pair<byte[],byte[]> getStartAndStopRow();

    protected Pair<byte[], byte[]> generateStartAndStopRows(int maxRange) {
      int start = this.rand.nextInt(Integer.MAX_VALUE) % totalRows;
      int stop = start + maxRange;
      return new Pair<byte[],byte[]>(format(start), format(stop));
    }

    @Override
    protected int getReportingPeriod() {
      int period = this.perClientRunRows / 100;
      return period == 0? this.perClientRunRows: period;
    }
  }

  static class RandomScanWithRange10Test extends RandomScanWithRangeTest {
    RandomScanWithRange10Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10);
    }
  }

  static class RandomScanWithRange100Test extends RandomScanWithRangeTest {
    RandomScanWithRange100Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(100);
    }
  }

  static class RandomScanWithRange1000Test extends RandomScanWithRangeTest {
    RandomScanWithRange1000Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(1000);
    }
  }

  static class RandomScanWithRange10000Test extends RandomScanWithRangeTest {
    RandomScanWithRange10000Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10000);
    }
  }

  static class RandomReadTest extends Test {
    private final int everyN;
    private final boolean reportLatency;
    private final double[] times;
    private final int multiGet;
    private ArrayList<Get> gets;
    int idx = 0;

    RandomReadTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
      everyN = (int) (this.totalRows / (this.totalRows * this.sampleRate));
      LOG.info("Sampling 1 every " + everyN + " out of " + perClientRunRows + " total rows.");
      this.reportLatency = options.isReportLatency();
      this.multiGet = options.getMultiGet();
      if (this.multiGet > 0) {
        LOG.info("MultiGet enabled. Sending GETs in batches of " + this.multiGet + ".");
        this.gets = new ArrayList<Get>(this.multiGet);
      }
      if (this.reportLatency) {
        this.times = new double[(int) Math.ceil(this.perClientRunRows * this.sampleRate / Math.max(1, this.multiGet))];
      } else {
        this.times = null;
      }
    }

    @Override
    void testRow(final int i) throws IOException {
      if (i % everyN == 0) {
        Get get = new Get(getRandomRow(this.rand, this.totalRows));
        get.addColumn(FAMILY_NAME, QUALIFIER_NAME);
        if (this.multiGet > 0) {
          this.gets.add(get);
          if (this.gets.size() == this.multiGet) {
            long start = System.nanoTime();
            this.table.get(this.gets);
            if (this.reportLatency) {
              times[idx++] = (System.nanoTime() - start) / 1e6;
            }
            this.gets.clear();
          }
        } else {
          long start = System.nanoTime();
          this.table.get(get);
          if (this.reportLatency) {
            times[idx++] = (System.nanoTime() - start) / 1e6;
          }
        }
      }
    }

    @Override
    protected int getReportingPeriod() {
      int period = this.perClientRunRows / 100;
      return period == 0 ? this.perClientRunRows : period;
    }

    @Override
    protected void testTakedown() throws IOException {
      if (this.gets != null && this.gets.size() > 0) {
        this.table.get(gets);
        this.gets.clear();
      }
      super.testTakedown();
      if (this.reportLatency) {
        Arrays.sort(times);
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (double t : times) {
          ds.addValue(t);
        }
        LOG.info("randomRead latency log (ms), on " + times.length + " measures");
        LOG.info("99.9999% = " + ds.getPercentile(99.9999d));
        LOG.info(" 99.999% = " + ds.getPercentile(99.999d));
        LOG.info("  99.99% = " + ds.getPercentile(99.99d));
        LOG.info("   99.9% = " + ds.getPercentile(99.9d));
        LOG.info("     99% = " + ds.getPercentile(99d));
        LOG.info("     95% = " + ds.getPercentile(95d));
        LOG.info("     90% = " + ds.getPercentile(90d));
        LOG.info("     80% = " + ds.getPercentile(80d));
        LOG.info("Standard Deviation = " + ds.getStandardDeviation());
        LOG.info("Mean = " + ds.getMean());
      }
    }
  }

  static class RandomWriteTest extends Test {
    RandomWriteTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      byte[] row = getRandomRow(this.rand, this.totalRows);
      Put put = new Put(row);
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      if (useTags) {
        byte[] tag = generateData(this.rand, TAG_LENGTH);
        Tag[] tags = new Tag[noOfTags];
        for (int n = 0; n < noOfTags; n++) {
          Tag t = new Tag((byte) n, tag);
          tags[n] = t;
        }
        KeyValue kv = new KeyValue(row, FAMILY_NAME, QUALIFIER_NAME, HConstants.LATEST_TIMESTAMP,
            value, tags);
        put.add(kv);
      } else {
        put.add(FAMILY_NAME, QUALIFIER_NAME, value);
      }
      put.setDurability(writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      table.put(put);
    }
  }


  static class ScanTest extends Test {
    private ResultScanner testScanner;

    ScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testTakedown() throws IOException {
      if (this.testScanner != null) {
        this.testScanner.close();
      }
      super.testTakedown();
    }


    @Override
    void testRow(final int i) throws IOException {
      if (this.testScanner == null) {
        Scan scan = new Scan(format(this.startRow));
        scan.setCaching(30);
        scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
        this.testScanner = table.getScanner(scan);
      }
      testScanner.next();
    }

  }

  static class SequentialReadTest extends Test {
    SequentialReadTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Get get = new Get(format(i));
      get.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      table.get(get);
    }
  }

  static class SequentialWriteTest extends Test {
    SequentialWriteTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      byte[] row = format(i);
      Put put = new Put(row);
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      if (useTags) {
        byte[] tag = generateData(this.rand, TAG_LENGTH);
        Tag[] tags = new Tag[noOfTags];
        for (int n = 0; n < noOfTags; n++) {
          Tag t = new Tag((byte) n, tag);
          tags[n] = t;
        }
        KeyValue kv = new KeyValue(row, FAMILY_NAME, QUALIFIER_NAME, HConstants.LATEST_TIMESTAMP,
            value, tags);
        put.add(kv);
      } else {
        put.add(FAMILY_NAME, QUALIFIER_NAME, value);
      }
      put.setDurability(writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      table.put(put);
    }
  }

  static class FilteredScanTest extends Test {
    protected static final Log LOG = LogFactory.getLog(FilteredScanTest.class.getName());

    FilteredScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(int i) throws IOException {
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      Scan scan = constructScan(value);
      ResultScanner scanner = null;
      try {
        scanner = this.table.getScanner(scan);
        while (scanner.next() != null) {
        }
      } finally {
        if (scanner != null) scanner.close();
      }
    }

    protected Scan constructScan(byte[] valuePrefix) throws IOException {
      Filter filter = new SingleColumnValueFilter(
          FAMILY_NAME, QUALIFIER_NAME, CompareFilter.CompareOp.EQUAL,
          new BinaryComparator(valuePrefix)
      );
      Scan scan = new Scan();
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      scan.setFilter(filter);
      return scan;
    }
  }

  /**
   * Compute a throughput rate in MB/s.
   * @param rows Number of records consumed.
   * @param timeMs Time taken in milliseconds.
   * @return String value with label, ie '123.76 MB/s'
   */
  private static String calculateMbps(int rows, long timeMs) {
    // MB/s = ((totalRows * ROW_SIZE_BYTES) / totalTimeMS)
    //        * 1000 MS_PER_SEC / (1024 * 1024) BYTES_PER_MB
    BigDecimal rowSize =
      BigDecimal.valueOf(ROW_LENGTH + VALUE_LENGTH + FAMILY_NAME.length + QUALIFIER_NAME.length);
    BigDecimal mbps = BigDecimal.valueOf(rows).multiply(rowSize, CXT)
      .divide(BigDecimal.valueOf(timeMs), CXT).multiply(MS_PER_SEC, CXT)
      .divide(BYTES_PER_MB, CXT);
    return FMT.format(mbps) + " MB/s";
  }

  /*
   * Format passed integer.
   * @param number
   * @return Returns zero-prefixed ROW_LENGTH-byte wide decimal version of passed
   * number (Does absolute in case number is negative).
   */
  public static byte [] format(final int number) {
    byte [] b = new byte[ROW_LENGTH];
    int d = Math.abs(number);
    for (int i = b.length - 1; i >= 0; i--) {
      b[i] = (byte)((d % 10) + '0');
      d /= 10;
    }
    return b;
  }

  /*
   * This method takes some time and is done inline uploading data.  For
   * example, doing the mapfile test, generation of the key and value
   * consumes about 30% of CPU time.
   * @return Generated random value to insert into a table cell.
   */
  public static byte[] generateData(final Random r, int length) {
    byte [] b = new byte [length];
    int i = 0;

    for(i = 0; i < (length-8); i += 8) {
      b[i] = (byte) (65 + r.nextInt(26));
      b[i+1] = b[i];
      b[i+2] = b[i];
      b[i+3] = b[i];
      b[i+4] = b[i];
      b[i+5] = b[i];
      b[i+6] = b[i];
      b[i+7] = b[i];
    }

    byte a = (byte) (65 + r.nextInt(26));
    for(; i < length; i++) {
      b[i] = a;
    }
    return b;
  }

  static byte [] getRandomRow(final Random random, final int totalRows) {
    return format(random.nextInt(Integer.MAX_VALUE) % totalRows);
  }

  long runOneClient(final Class<? extends Test> cmd, final int startRow,
      final int perClientRunRows, final int totalRows, final float sampleRate,
      boolean flushCommits, boolean writeToWAL, boolean useTags, int noOfTags,
      boolean reportLatency, int multiGet, HConnection connection, final Status status)
  throws IOException {
    status.setStatus("Start " + cmd + " at offset " + startRow + " for " +
      perClientRunRows + " rows");
    long totalElapsedTime = 0;

    TestOptions options = new TestOptions(startRow, perClientRunRows,
      totalRows, sampleRate, N, tableName, flushCommits, writeToWAL, useTags, noOfTags,
      reportLatency, multiGet, connection);
    final Test t;
    try {
      Constructor<? extends Test> constructor = cmd.getDeclaredConstructor(
          Configuration.class, TestOptions.class, Status.class);
      t = constructor.newInstance(getConf(), options, status);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Invalid command class: " +
          cmd.getName() + ".  It does not provide a constructor as described by" +
          "the javadoc comment.  Available constructors are: " +
          Arrays.toString(cmd.getConstructors()));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to construct command class", e);
    }
    totalElapsedTime = t.test();

    status.setStatus("Finished " + cmd + " in " + totalElapsedTime +
      "ms at offset " + startRow + " for " + perClientRunRows + " rows" +
      " (" + calculateMbps((int)(perClientRunRows * sampleRate), totalElapsedTime) + ")");
    return totalElapsedTime;
  }

  private void runNIsOne(final Class<? extends Test> cmd) throws IOException {
    Status status = new Status() {
      public void setStatus(String msg) throws IOException {
        LOG.info(msg);
      }
    };

    HBaseAdmin admin = null;
    try {
      admin = new HBaseAdmin(getConf());
      checkTable(admin);
      runOneClient(cmd, 0, this.R, this.R, this.sampleRate, this.flushCommits,
      this.writeToWAL, this.useTags, this.noOfTags, this.reportLatency, this.multiGet,
        this.connection, status);
    } catch (Exception e) {
      LOG.error("Failed", e);
    } finally {
      if (admin != null) admin.close();
    }
  }

  private void runTest(final Class<? extends Test> cmd) throws IOException,
      InterruptedException, ClassNotFoundException {
    if (N == 1) {
      // If there is only one client and one HRegionServer, we assume nothing
      // has been set up at all.
      runNIsOne(cmd);
    } else {
      // Else, run
      runNIsMoreThanOne(cmd);
    }
  }

  protected void printUsage() {
    printUsage(null);
  }

  protected void printUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() + " \\");
    System.err.println("  [--nomapred] [--rows=ROWS] [--table=NAME] \\");
    System.err.println("  [--compress=TYPE] [--blockEncoding=TYPE] " +
      "[-D<property=value>]* <command> <nclients>");
    System.err.println();
    System.err.println("Options:");
    System.err.println(" nomapred        Run multiple clients using threads " +
      "(rather than use mapreduce)");
    System.err.println(" rows            Rows each client runs. Default: One million");
    System.err.println(" sampleRate      Execute test on a sample of total " +
      "rows. Only supported by randomRead. Default: 1.0");
    System.err.println(" table           Alternate table name. Default: 'TestTable'");
    System.err.println(" compress        Compression type to use (GZ, LZO, ...). Default: 'NONE'");
    System.err.println(" flushCommits    Used to determine if the test should flush the table. " +
      "Default: false");
    System.err.println(" writeToWAL      Set writeToWAL on puts. Default: True");
    System.err.println(" presplit        Create presplit table. Recommended for accurate perf " +
      "analysis (see guide).  Default: disabled");
    System.err.println(" inmemory        Tries to keep the HFiles of the CF " +
      "inmemory as far as possible. Not guaranteed that reads are always served " +
      "from memory.  Default: false");
    System.err.println(" usetags         Writes tags along with KVs. Use with HFile V3. " +
      "Default: false");
    System.err.println(" numoftags       Specify the no of tags that would be needed. " +
       "This works only if usetags is true.");
    System.err.println(" latency         Set to report operation latencies. " +
      "Currently only supported by randomRead test. Default: False");
    System.err.println();
    System.err.println(" Note: -D properties will be applied to the conf used. ");
    System.err.println("  For example: ");
    System.err.println("   -Dmapred.output.compress=true");
    System.err.println("   -Dmapreduce.task.timeout=60000");
    System.err.println();
    System.err.println("Command:");
    for (CmdDescriptor command : commands.values()) {
      System.err.println(String.format(" %-15s %s", command.getName(), command.getDescription()));
    }
    System.err.println();
    System.err.println("Args:");
    System.err.println(" nclients        Integer. Required. Total number of " +
      "clients (and HRegionServers)");
    System.err.println("                 running: 1 <= value <= 500");
    System.err.println("Examples:");
    System.err.println(" To run a single evaluation client:");
    System.err.println(" $ bin/hbase " + this.getClass().getName()
        + " sequentialWrite 1");
  }

  private void getArgs(final int start, final String[] args) {
    if(start + 1 > args.length) {
      throw new IllegalArgumentException("must supply the number of clients");
    }
    N = Integer.parseInt(args[start]);
    if (N < 1) {
      throw new IllegalArgumentException("Number of clients must be > 1");
    }
    // Set total number of rows to write.
    this.R = this.R * N;
  }

  public int run(String[] args) throws Exception {
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    int errCode = -1;
    if (args.length < 1) {
      printUsage();
      return errCode;
    }

    try {
      // MR-NOTE: if you are adding a property that is used to control an operation
      // like put(), get(), scan(), ... you must also add it as part of the MR 
      // input, take a look at writeInputFile().
      // Then you must adapt the LINE_PATTERN input regex,
      // and parse the argument, take a look at PEInputFormat.getSplits().
      
      for (int i = 0; i < args.length; i++) {
        String cmd = args[i];
        if (cmd.equals("-h") || cmd.startsWith("--h")) {
          printUsage();
          errCode = 0;
          break;
        }

        final String nmr = "--nomapred";
        if (cmd.startsWith(nmr)) {
          this.nomapred = true;
          continue;
        }

        final String rows = "--rows=";
        if (cmd.startsWith(rows)) {
          this.R = Integer.parseInt(cmd.substring(rows.length()));
          continue;
        }

        final String sampleRate = "--sampleRate=";
        if (cmd.startsWith(sampleRate)) {
          this.sampleRate = Float.parseFloat(cmd.substring(sampleRate.length()));
          continue;
        }

        final String table = "--table=";
        if (cmd.startsWith(table)) {
          this.tableName = TableName.valueOf(cmd.substring(table.length()));
          continue;
        }

        final String compress = "--compress=";
        if (cmd.startsWith(compress)) {
          this.compression = Compression.Algorithm.valueOf(cmd.substring(compress.length()));
          continue;
        }

        final String blockEncoding = "--blockEncoding=";
        if (cmd.startsWith(blockEncoding)) {
          this.blockEncoding = DataBlockEncoding.valueOf(cmd.substring(blockEncoding.length()));
          continue;
        }

        final String flushCommits = "--flushCommits=";
        if (cmd.startsWith(flushCommits)) {
          this.flushCommits = Boolean.parseBoolean(cmd.substring(flushCommits.length()));
          continue;
        }

        final String writeToWAL = "--writeToWAL=";
        if (cmd.startsWith(writeToWAL)) {
          this.writeToWAL = Boolean.parseBoolean(cmd.substring(writeToWAL.length()));
          continue;
        }

        final String presplit = "--presplit=";
        if (cmd.startsWith(presplit)) {
          this.presplitRegions = Integer.parseInt(cmd.substring(presplit.length()));
          continue;
        }
        
        final String inMemory = "--inmemory=";
        if (cmd.startsWith(inMemory)) {
          this.inMemoryCF = Boolean.parseBoolean(cmd.substring(inMemory.length()));
          continue;
        }

        final String latency = "--latency";
        if (cmd.startsWith(latency)) {
          this.reportLatency = true;
          continue;
        }

        final String multiGet = "--multiGet=";
        if (cmd.startsWith(multiGet)) {
          this.multiGet = Integer.parseInt(cmd.substring(multiGet.length()));
          continue;
        }

        this.connection = HConnectionManager.createConnection(getConf());
        
        final String useTags = "--usetags=";
        if (cmd.startsWith(useTags)) {
          this.useTags = Boolean.parseBoolean(cmd.substring(useTags.length()));
          continue;
        }

        final String noOfTags = "--nooftags=";
        if (cmd.startsWith(noOfTags)) {
          this.noOfTags = Integer.parseInt(cmd.substring(noOfTags.length()));
          continue;
        }

        Class<? extends Test> cmdClass = determineCommandClass(cmd);
        if (cmdClass != null) {
          getArgs(i + 1, args);
          runTest(cmdClass);
          errCode = 0;
          break;
        }

        printUsage();
        break;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return errCode;
  }

  private Class<? extends Test> determineCommandClass(String cmd) {
    CmdDescriptor descriptor = commands.get(cmd);
    return descriptor != null ? descriptor.getCmdClass() : null;
  }

  public static void main(final String[] args) throws Exception {
    int res = ToolRunner.run(new PerformanceEvaluation(HBaseConfiguration.create()), args);
    System.exit(res);
  }
}
