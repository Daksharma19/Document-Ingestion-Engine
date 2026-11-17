package com.docextract;

import com.docextract.eval.EvalCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class DocextractApplication {

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && args[0].equals("eval")) {
			System.exit(EvalCli.run(Arrays.copyOfRange(args, 1, args.length)));
		}
		SpringApplication.run(DocextractApplication.class, args);
	}

}
