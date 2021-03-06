package org.nmdp.netmhc

/**
 *    netMHC Apache Spark CLI
 *
 *    Copyright (c) 2017-2018 National Marrow Donor Program (NMDP)
 *    This library is free software; you can redistribute it and/or modify it
 *    under the terms of the GNU Lesser General Public License as published
 *    by the Free Software Foundation; either version 3 of the License, or (at
 *    your option) any later version.
 *    This library is distributed in the hope that it will be useful, but WITHOUT
 *    ANY WARRANTY; with out even the implied warranty of MERCHANTABILITY or
 *    FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public
 *    License for more details.
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this library;  if not, write to the Free Software Foundation,
 *    Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA.
 *    > http://www.gnu.org/licenses/lgpl.html
 */
import org.apache.log4j.{Level, LogManager}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{concat, lit}

case class Config(input: String = null,
                  output: String = null,
                  alleles: String = null,
                  format: String = "parquet")

object NetMHC extends App {

  val parser = new scopt.OptionParser[Config]("spark-submit netmhc-spark-1.0-SNAPSHOT.jar") {
    head("netmhc-spark", "1.0")
    opt[String]('i', "input") required() action { (x, c) =>
      c.copy(input = x) } text("input is the input path")
    opt[String]('o', "output") required() action { (x, c) =>
      c.copy(output = x) } text("output is the output path")
    opt[String]('a', "alleles") required() action { (x, c) =>
      c.copy(alleles = x) } text("alleles is the list of HLA alleles to use")
    opt[String]('f', "format") action { (x, c) =>
      c.copy(format = x) } text("format is output format type (default = parquet)")
  }

  // parser.parse returns Option[C]
  parser.parse(args, Config()) map { config =>

    // parse arguments
    val input = config.input
    val output = config.output
    val allelefile = config.alleles
    val format = config.format

    // initialize logger
    val log = LogManager.getRootLogger
    log.setLevel(Level.INFO)

    log.info("input==" + input)
    log.info("output==" + output)
    log.info("allelelist==" + allelefile)

    val spark = SparkSession
      .builder()
      .appName("NetMHC")
      .config("spark.executor.instances", "15")
      .config("spark.shuffle.compress", "true")
      .config("spark.io.compression.codec", "snappy")
      .getOrCreate()

    import spark.implicits._

    // Create list of alleles from allele list file
    val alleles = spark.sparkContext.textFile(allelefile)

    // Read in peptide data - one peptide per line
    val peps = spark.sparkContext.textFile(input)

    val prdd = alleles.cartesian(peps).toDF("Allele","Pep").
               select(concat($"Pep", lit(","), $"Allele").alias("cmd")).rdd.
               map(row => row(0))

    // Get size of rdd
    val cnt = prdd.count.toInt / 200

    val partitions = cnt.toInt

    // repartition by how many rows
    val rdd = prdd.repartition(partitions)

    // regex for macthing the rows with the binding affinity
    val binding = """(^\d.+)""".r

    val cmd = "/home/hadoop/run_netMHC.sh"

    val pdf = rdd.pipe(cmd).
                     map(_.replaceAll("^ *","")).
                     filter(_.matches(binding.toString)).
                     map(_.replaceAll(" +",",")).
                     map(_.split(",")).
                     filter(_.length > 10).
                     map(attributes => (attributes(1)
                        ,attributes(2)
                        ,attributes(3)
                        ,attributes(4)
                        ,attributes(5)
                        ,attributes(6)
                        ,attributes(7)
                        ,attributes(8)
                        ,attributes(9)
                        ,attributes(10)
                        ,attributes(11)
                        ,attributes(12)
                        ,attributes(13)
                        )).toDF("HLA","peptide","Core","Offset","I_pos","I_len","D_pos",
                      "D_len","iCore","Identity","log50k","Affinity",
                      "Rank").
                      selectExpr("HLA","peptide","Core",
                        "cast(Offset as int) as Offset",
                        "cast(I_pos as int) as I_pos",
                        "cast(I_len as int) as I_len",
                        "cast(D_pos as int) as D_pos",
                        "cast(D_len as int) as D_len",
                        "iCore",
                        "Identity",
                        "cast(log50k as int) as log50k",
                        "cast(Affinity as float) as Affinity",
                        "cast(Rank as float) as Rank"
                      )

    //pdf.show()

    // Save results to the specified output directory
    pdf.write.format(format).save(output)
  }
}
